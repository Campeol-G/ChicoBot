package dev.chico.bot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueueLiveStoreTest {

  @Test
  void replaceReturnsPreviousEntry() {
    QueueLive.Store store = new QueueLive.Store();

    assertEquals(null, store.replace(1L, new QueueLive.Entry(10L, 20L)));
    QueueLive.Entry previous = store.replace(1L, new QueueLive.Entry(11L, 21L));

    assertEquals(new QueueLive.Entry(10L, 20L), previous);
    assertEquals(new QueueLive.Entry(11L, 21L), store.get(1L).orElseThrow());
  }

  @Test
  void removeDropsOnlyThatGuild() {
    QueueLive.Store store = new QueueLive.Store();
    store.replace(1L, new QueueLive.Entry(10L, 20L));
    store.replace(2L, new QueueLive.Entry(11L, 21L));

    store.remove(1L);

    assertTrue(store.get(1L).isEmpty());
    assertTrue(store.get(2L).isPresent());
  }
}
