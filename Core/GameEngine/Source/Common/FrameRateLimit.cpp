/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 TheSuperHackers
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "PreRTS.h"
#include "Common/FrameRateLimit.h"

// GeneralsX @build BenderAI 12/02/2026 Platform-specific high-resolution timing
#ifndef _WIN32
#include <time.h>    // clock_gettime, nanosleep
#include <unistd.h>  // usleep (fallback)
#endif

#ifdef __ANDROID__
#include <dlfcn.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/resource.h>

// GeneralsX @performance Android port 10/10/2026 Tell Android how much work each frame is.
// The whole engine -- logic, rendering, audio mixing calls -- runs on one thread, and a
// big battle can need more than the CPU's current clock gives that thread. By default the
// kernel only notices after the frame is already late (it ramps frequency from past load),
// which shows as frame drops in large fights. Android 13 added ADPF performance hints
// (APerformanceHint_*): the game states its frame budget and reports each frame's actual
// work time, and the system raises clocks or moves the thread to a faster core before
// frames are missed, and lowers them again when there is slack.
//
// This only changes WHEN the CPU runs fast, never what the engine computes, so it cannot
// affect the simulation, replays or multiplayer sync. The functions are looked up at run
// time (minSdk is 28, the API is 33): on older Android, or a device without support,
// nothing happens. The work time is measured where FrameRateLimit::wait() is entered, i.e.
// before the limiter sleeps, so idle time is never reported as work.
//
// The engine thread's nice value also goes from 0 to -4 (Android's THREAD_PRIORITY_DISPLAY,
// below the -8 of Android's own render threads and of real-time audio callbacks), so
// background app threads take time slices from it less often.
namespace
{
	typedef void* (*PfnGetManager)();
	typedef void* (*PfnCreateSession)(void* manager, const int32_t* threadIds, size_t size, int64_t initialTargetWorkDurationNanos);
	typedef int (*PfnUpdateTarget)(void* session, int64_t targetDurationNanos);
	typedef int (*PfnReportActual)(void* session, int64_t actualDurationNanos);

	const int ENGINE_THREAD_NICE = -4;
	const Int64 UNCAPPED_TARGET_NS = 1000000000 / 60;

	struct AndroidFrameHint
	{
		bool initialized;
		void* session;
		PfnUpdateTarget updateTarget;
		PfnReportActual reportActual;
		Int64 currentTargetNs;
	};

	AndroidFrameHint s_frameHint = { false, nullptr, nullptr, nullptr, 0 };

	Int64 targetNsFor(UnsignedInt maxFps)
	{
		if (maxFps == 0 || maxFps > 1000)
			return UNCAPPED_TARGET_NS;
		return 1000000000 / static_cast<Int64>(maxFps);
	}

	void initFrameHint(Int64 targetNs)
	{
		s_frameHint.initialized = true;
		const pid_t tid = gettid();

		errno = 0;
		const int currentNice = getpriority(PRIO_PROCESS, tid);
		if (errno == 0 && currentNice > ENGINE_THREAD_NICE)
		{
			if (setpriority(PRIO_PROCESS, tid, ENGINE_THREAD_NICE) == 0)
				fprintf(stderr, "[GX-PERF-HINT] engine thread %d nice %d -> %d\n", (int)tid, currentNice, ENGINE_THREAD_NICE);
			else
				fprintf(stderr, "[GX-PERF-HINT] engine thread %d: setpriority failed (errno %d), keeping nice %d\n", (int)tid, errno, currentNice);
		}

		void* libandroid = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
		if (libandroid == nullptr)
		{
			fprintf(stderr, "[GX-PERF-HINT] libandroid.so not available, no performance hints\n");
			return;
		}
		PfnGetManager getManager = reinterpret_cast<PfnGetManager>(dlsym(libandroid, "APerformanceHint_getManager"));
		PfnCreateSession createSession = reinterpret_cast<PfnCreateSession>(dlsym(libandroid, "APerformanceHint_createSession"));
		PfnUpdateTarget updateTarget = reinterpret_cast<PfnUpdateTarget>(dlsym(libandroid, "APerformanceHint_updateTargetWorkDuration"));
		PfnReportActual reportActual = reinterpret_cast<PfnReportActual>(dlsym(libandroid, "APerformanceHint_reportActualWorkDuration"));
		if (getManager == nullptr || createSession == nullptr || updateTarget == nullptr || reportActual == nullptr)
		{
			fprintf(stderr, "[GX-PERF-HINT] performance hints need Android 13+, not available here\n");
			return;
		}
		void* manager = getManager();
		if (manager == nullptr)
		{
			fprintf(stderr, "[GX-PERF-HINT] no performance hint manager on this device\n");
			return;
		}
		const int32_t threadIds[1] = { static_cast<int32_t>(tid) };
		void* session = createSession(manager, threadIds, 1, targetNs);
		if (session == nullptr)
		{
			fprintf(stderr, "[GX-PERF-HINT] device declined a performance hint session\n");
			return;
		}
		s_frameHint.session = session;
		s_frameHint.updateTarget = updateTarget;
		s_frameHint.reportActual = reportActual;
		s_frameHint.currentTargetNs = targetNs;
		fprintf(stderr, "[GX-PERF-HINT] session active for engine thread %d, target %.2f ms\n", (int)tid, targetNs / 1000000.0);
	}

	/** Called once per frame on the engine thread with the time the frame spent working. */
	void reportFrameWork(Int64 workNs, UnsignedInt maxFps)
	{
		const Int64 targetNs = targetNsFor(maxFps);
		if (!s_frameHint.initialized)
			initFrameHint(targetNs);
		if (s_frameHint.session == nullptr)
			return;
		if (targetNs != s_frameHint.currentTargetNs)
		{
			s_frameHint.updateTarget(s_frameHint.session, targetNs);
			s_frameHint.currentTargetNs = targetNs;
		}
		// The API rejects non-positive durations; a frame always does some work.
		s_frameHint.reportActual(s_frameHint.session, workNs > 0 ? workNs : 1);
	}
}
#endif

FrameRateLimit::FrameRateLimit()
{
#ifdef _WIN32
	LARGE_INTEGER freq;
	LARGE_INTEGER start;
	QueryPerformanceFrequency(&freq);
	QueryPerformanceCounter(&start);
	m_freq = freq.QuadPart;
	m_start = start.QuadPart;
#else
	// Linux: Use CLOCK_MONOTONIC for high-resolution timing
	struct timespec start;
	clock_gettime(CLOCK_MONOTONIC, &start);
	m_freq = 1000000000; // nanoseconds per second
	m_start = static_cast<Int64>(start.tv_sec) * 1000000000 + start.tv_nsec;
#endif
}

Real FrameRateLimit::wait(UnsignedInt maxFps)
{
	PROFILER_SECTION;

	// GeneralsX @bugfix BenderAI 11/05/2026 Validate FPS limit to prevent division by zero and underflow
	// Skip limiting if maxFps is 0 or extremely high (uncapped mode)
	if (maxFps == 0 || maxFps > 1000000)
	{
		// Uncapped or invalid: just return elapsed time without limiting
#ifdef _WIN32
		LARGE_INTEGER tick;
		QueryPerformanceCounter(&tick);
		double elapsedSeconds = static_cast<double>(tick.QuadPart - m_start) / m_freq;
		m_start = tick.QuadPart;
		return (Real)elapsedSeconds;
#else
		struct timespec tick;
		clock_gettime(CLOCK_MONOTONIC, &tick);
		Int64 tickValue = static_cast<Int64>(tick.tv_sec) * 1000000000 + tick.tv_nsec;
		double elapsedSeconds = static_cast<double>(tickValue - m_start) / static_cast<double>(m_freq);
#ifdef __ANDROID__
		reportFrameWork(tickValue - m_start, maxFps);
#endif
		m_start = tickValue;
		return static_cast<Real>(elapsedSeconds);
#endif
	}

#ifdef _WIN32
	LARGE_INTEGER tick;
	QueryPerformanceCounter(&tick);
	double elapsedSeconds = static_cast<double>(tick.QuadPart - m_start) / m_freq;
	const double targetSeconds = 1.0 / maxFps;
	const double sleepSeconds = targetSeconds - elapsedSeconds - 0.002; // leave ~2ms for spin wait

	if (sleepSeconds > 0.0)
	{
		// Non busy wait with Munkee sleep
		DWORD dwMilliseconds = static_cast<DWORD>(sleepSeconds * 1000);
		Sleep(dwMilliseconds);
	}

	// Busy wait for remaining time
	do
	{
		QueryPerformanceCounter(&tick);
		elapsedSeconds = static_cast<double>(tick.QuadPart - m_start) / m_freq;
	}
	while (elapsedSeconds < targetSeconds);

	m_start = tick.QuadPart;
	return (Real)elapsedSeconds;
#else
	// Linux: Use CLOCK_MONOTONIC for high-resolution timing
	struct timespec tick;
	clock_gettime(CLOCK_MONOTONIC, &tick);
	Int64 tickValue = static_cast<Int64>(tick.tv_sec) * 1000000000 + tick.tv_nsec;
	double elapsedSeconds = static_cast<double>(tickValue - m_start) / static_cast<double>(m_freq);
#ifdef __ANDROID__
	// Before any sleeping: this is the frame's own work.
	reportFrameWork(tickValue - m_start, maxFps);
#endif
	const double targetSeconds = 1.0 / maxFps;
#ifdef __ANDROID__
	// GeneralsX @performance Android port 10/10/2026 Spin 0.5 ms instead of 2 ms. Every frame
	// the limiter busy-waits the last stretch on the engine's core; on a phone that is pure
	// heat (about 12% of a core at 60 fps), and heat is what throttles the clocks later in a
	// long battle. Android's nanosleep wakes within tens of microseconds, so 0.5 ms keeps the
	// same frame timing. Pacing only -- the simulation never sees this.
	const double spinSeconds = 0.0005;
#else
	const double spinSeconds = 0.002;
#endif
	const double sleepSeconds = targetSeconds - elapsedSeconds - spinSeconds; // leave a little for spin wait

	if (sleepSeconds > 0.0)
	{
		// Non busy wait with nanosleep
		struct timespec sleepTime;
		sleepTime.tv_sec = static_cast<time_t>(sleepSeconds);
		sleepTime.tv_nsec = static_cast<long>((sleepSeconds - sleepTime.tv_sec) * 1000000000);
		nanosleep(&sleepTime, nullptr);
	}

	// Busy wait for remaining time
	do
	{
		clock_gettime(CLOCK_MONOTONIC, &tick);
		tickValue = static_cast<Int64>(tick.tv_sec) * 1000000000 + tick.tv_nsec;
		elapsedSeconds = static_cast<double>(tickValue - m_start) / static_cast<double>(m_freq);
	}
	while (elapsedSeconds < targetSeconds);

	m_start = tickValue;
	return static_cast<Real>(elapsedSeconds);
#endif
}


const UnsignedInt RenderFpsPreset::s_fpsValues[] = {
#if defined(GENERALS_ONLINE_HIGH_FPS_SERVER)
	60, 65, 70, 72, 75, 80, 85, 90, 100, 110, 120, 144, 240, 480, UncappedFpsValue };
#else
	30, 50, 56, 60, 65, 70, 72, 75, 80, 85, 90, 100, 110, 120, 144, 240, 480, UncappedFpsValue };
#endif

static_assert(LOGICFRAMES_PER_SECOND <= GENERALS_ONLINE_HIGH_FPS_LIMIT, "Min FPS values need to be revisited!");

UnsignedInt RenderFpsPreset::getNextFpsValue(UnsignedInt value)
{
	const Int first = 0;
	const Int last = ARRAY_SIZE(s_fpsValues) - 1;
	for (Int i = first; i < last; ++i)
	{
		if (value >= s_fpsValues[i] && value < s_fpsValues[i + 1])
		{
			return s_fpsValues[i + 1];
		}
	}
	return s_fpsValues[last];
}

UnsignedInt RenderFpsPreset::getPrevFpsValue(UnsignedInt value)
{
	const Int first = 0;
	const Int last = ARRAY_SIZE(s_fpsValues) - 1;
	for (Int i = last; i > first; --i)
	{
		if (value <= s_fpsValues[i] && value > s_fpsValues[i - 1])
		{
			return s_fpsValues[i - 1];
		}
	}
	return s_fpsValues[first];
}

UnsignedInt RenderFpsPreset::changeFpsValue(UnsignedInt value, FpsValueChange change)
{
	switch (change)
	{
	default:
	case FpsValueChange_Increase: return getNextFpsValue(value);
	case FpsValueChange_Decrease: return getPrevFpsValue(value);
	}
}


UnsignedInt LogicTimeScaleFpsPreset::getNextFpsValue(UnsignedInt value)
{
	return value + StepFpsValue;
}

UnsignedInt LogicTimeScaleFpsPreset::getPrevFpsValue(UnsignedInt value)
{
	if (value - StepFpsValue < MinFpsValue)
	{
		return MinFpsValue;
	}
	else
	{
		return value - StepFpsValue;
	}
}

UnsignedInt LogicTimeScaleFpsPreset::changeFpsValue(UnsignedInt value, FpsValueChange change)
{
	switch (change)
	{
	default:
	case FpsValueChange_Increase: return getNextFpsValue(value);
	case FpsValueChange_Decrease: return getPrevFpsValue(value);
	}
}
