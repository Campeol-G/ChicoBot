package dev.chico.bot.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.track.YoutubeAudioTrack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GuildMusicManagerTest {

  private static final YoutubeAudioSourceManager YOUTUBE = new YoutubeAudioSourceManager();

  private static AudioTrack track(String title) {
    return new YoutubeAudioTrack(
        new AudioTrackInfo(title, "artist", 200_000L, title, false, "https://youtube.com/watch"),
        YOUTUBE);
  }

  private static GuildMusicManager manager(int queueMax) {
    AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    return new GuildMusicManager(playerManager, queueMax);
  }

  @Test
  void enqueueAllKeepsOrderAndReturnsCount() {
    GuildMusicManager manager = manager(200);

    int accepted = manager.enqueueAll(List.of(track("a"), track("b"), track("c")));

    assertEquals(3, accepted);
    assertEquals("a", manager.player().getPlayingTrack().getInfo().title);
    assertEquals(
        List.of("b", "c"),
        manager.scheduler().queueView().snapshot().stream()
            .map(t -> t.getInfo().title)
            .toList());
  }

  @Test
  void enqueueAllStopsWhenFull() {
    GuildMusicManager manager = manager(2);

    int accepted =
        manager.enqueueAll(
            List.of(track("a"), track("b"), track("c"), track("d"), track("e")));

    assertEquals(3, accepted);
    assertEquals(2, manager.scheduler().queueView().size());
  }

  @Test
  void concurrentBatchesDoNotInterleave() throws Exception {
    GuildMusicManager manager = manager(200);
    List<AudioTrack> batchA = new ArrayList<>();
    List<AudioTrack> batchB = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      batchA.add(track("a-" + i));
      batchB.add(track("b-" + i));
    }
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);
    AtomicInteger acceptedA = new AtomicInteger();
    AtomicInteger acceptedB = new AtomicInteger();
    Thread threadA =
        new Thread(
            () -> {
              await(start);
              acceptedA.set(manager.enqueueAll(batchA));
              done.countDown();
            });
    Thread threadB =
        new Thread(
            () -> {
              await(start);
              acceptedB.set(manager.enqueueAll(batchB));
              done.countDown();
            });
    threadA.start();
    threadB.start();
    start.countDown();
    done.await();

    assertEquals(50, acceptedA.get());
    assertEquals(50, acceptedB.get());
    List<String> combined = new ArrayList<>();
    combined.add(manager.player().getPlayingTrack().getInfo().title);
    manager.scheduler().queueView().snapshot().stream()
        .map(t -> t.getInfo().title)
        .forEach(combined::add);
    assertEquals(100, combined.size());
    assertContiguous(combined, "a-");
    assertContiguous(combined, "b-");
  }

  private static void assertContiguous(List<String> combined, String prefix) {
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < combined.size(); i++) {
      if (combined.get(i).startsWith(prefix)) {
        indices.add(i);
      }
    }
    assertEquals(50, indices.size());
    Set<Integer> seen = new HashSet<>(indices);
    assertEquals(50, seen.size());
    int min = indices.stream().mapToInt(Integer::intValue).min().orElseThrow();
    int max = indices.stream().mapToInt(Integer::intValue).max().orElseThrow();
    assertEquals(49, max - min, "batch " + prefix + " is interleaved");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }
}
