package dev.chico.bot.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** Bounded FIFO queue. All operations are thread-safe. */
public final class TrackQueue<T> {

  private final int maxSize;
  private final Deque<T> items = new ArrayDeque<>();

  public TrackQueue(int maxSize) {
    this.maxSize = maxSize;
  }

  public synchronized boolean add(T item) {
    if (items.size() >= maxSize) {
      return false;
    }
    return items.offer(item);
  }

  /** Playlist-confirm path only: bypasses maxSize by design. */
  public synchronized boolean addUnbounded(T item) {
    return items.offer(item);
  }

  public synchronized Optional<T> poll() {
    return Optional.ofNullable(items.poll());
  }

  public synchronized Optional<T> peek() {
    return Optional.ofNullable(items.peek());
  }

  public synchronized boolean removeAt(int index) {
    if (index < 0 || index >= items.size()) {
      return false;
    }
    var it = items.iterator();
    for (int i = 0; i <= index; i++) {
      it.next();
      if (i == index) {
        it.remove();
        return true;
      }
    }
    return false;
  }

  public synchronized void clear() {
    items.clear();
  }

  public synchronized int size() {
    return items.size();
  }

  public synchronized List<T> snapshot() {
    return new ArrayList<>(items);
  }
}
