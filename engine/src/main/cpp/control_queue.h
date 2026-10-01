// Bounded, lock-free, single-producer/single-consumer queue of control frames.
//
// The audio callback must never block, and the sensor thread must never make it wait. A fixed-size
// ring with relaxed atomics is the whole mechanism: the producer only ever moves the tail, the
// consumer only ever moves the head, and neither touches memory the other is still using.
//
// Overflow policy: when the queue is full the **oldest** frame is discarded. Motion is only
// meaningful while it is current, and a stale frame pushed into the head of the queue would delay
// fresher motion behind it. Discrete events (ignite/retract) travel on their own queue and are
// never dropped, because losing a Stop would be a user-visible failure.
#pragma once

#include <atomic>
#include <cstddef>

#include "control_frame.h"

namespace lightsaber {

class ControlQueue {
 public:
  static constexpr size_t kCapacity = 256;  // power of two: index wraps with a mask
  static_assert((kCapacity & (kCapacity - 1)) == 0, "capacity must be a power of two");

  // Producer side. Safe to call only from the single producer thread.
  bool push(const ControlFrame& frame) {
    const size_t tail = tail_.load(std::memory_order_relaxed);
    const size_t next = (tail + 1) % kCapacity;
    if (next == head_.load(std::memory_order_acquire)) {
      // Full: drop the oldest to make room, rather than refusing fresh motion.
      head_.store((head_.load(std::memory_order_relaxed) + 1) % kCapacity, std::memory_order_release);
      ++dropped_;
    }
    buffer_[tail] = frame;
    tail_.store(next, std::memory_order_release);
    return true;
  }

  // Consumer side. Safe to call only from the audio thread.
  bool pop(ControlFrame& out) {
    const size_t head = head_.load(std::memory_order_relaxed);
    if (head == tail_.load(std::memory_order_acquire)) return false;
    out = buffer_[head];
    head_.store((head + 1) % kCapacity, std::memory_order_release);
    return true;
  }

  void clear() {
    head_.store(0, std::memory_order_release);
    tail_.store(0, std::memory_order_release);
  }

  size_t droppedCount() const { return dropped_.load(std::memory_order_relaxed); }

 private:
  ControlFrame buffer_[kCapacity]{};
  std::atomic<size_t> head_{0};
  std::atomic<size_t> tail_{0};
  std::atomic<size_t> dropped_{0};
};

// Discrete session events. Tiny by design: two lanes, never dropped.
enum class SessionEvent : int32_t { kIgnite = 1, kRetract = 2, kStopImmediate = 3 };

struct EventQueue {
  static constexpr size_t kCapacity = 16;

  bool push(SessionEvent e) {
    const size_t tail = tail_.load(std::memory_order_relaxed);
    const size_t next = (tail + 1) % kCapacity;
    if (next == head_.load(std::memory_order_acquire)) return false;  // never drop an event
    buffer_[tail] = e;
    tail_.store(next, std::memory_order_release);
    return true;
  }

  bool pop(SessionEvent& out) {
    const size_t head = head_.load(std::memory_order_relaxed);
    if (head == tail_.load(std::memory_order_acquire)) return false;
    out = buffer_[head];
    head_.store((head + 1) % kCapacity, std::memory_order_release);
    return true;
  }

  void clear() {
    head_.store(0, std::memory_order_release);
    tail_.store(0, std::memory_order_release);
  }

 private:
  SessionEvent buffer_[kCapacity]{};
  std::atomic<size_t> head_{0};
  std::atomic<size_t> tail_{0};
};

}  // namespace lightsaber