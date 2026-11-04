package dev.chico.bot.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TrackQueueTest {

  @Test
  void addPollKeepsOrder() {
    TrackQueue<String> queue = new TrackQueue<>(3);

    assertTrue(queue.add("a"));
    assertTrue(queue.add("b"));

    assertEquals(Optional.of("a"), queue.poll());
    assertEquals(Optional.of("b"), queue.peek());
    assertEquals(Optional.of("b"), queue.poll());
    assertEquals(Optional.empty(), queue.poll());
  }

  @Test
  void addFailsWhenFull() {
    TrackQueue<String> queue = new TrackQueue<>(2);

    assertTrue(queue.add("a"));
    assertTrue(queue.add("b"));
    assertFalse(queue.add("c"));
    assertEquals(2, queue.size());
  }

  @Test
  void removeAtShiftsRest() {
    TrackQueue<String> queue = new TrackQueue<>(3);
    queue.add("a");
    queue.add("b");
    queue.add("c");

    assertTrue(queue.removeAt(1));
    assertEquals(List.of("a", "c"), queue.snapshot());
    assertFalse(queue.removeAt(5));
    assertFalse(queue.removeAt(-1));
  }

  @Test
  void clearEmptiesQueue() {
    TrackQueue<String> queue = new TrackQueue<>(3);
    queue.add("a");
    queue.add("b");

    queue.clear();

    assertEquals(0, queue.size());
    assertEquals(Optional.empty(), queue.poll());
  }

  @Test
  void snapshotIsACopy() {
    TrackQueue<String> queue = new TrackQueue<>(3);
    queue.add("a");

    List<String> snapshot = queue.snapshot();
    queue.add("b");

    assertEquals(List.of("a"), snapshot);
    assertEquals(2, queue.size());
  }
}
