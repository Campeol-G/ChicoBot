package dev.chico.bot.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.TrackMarker;
import com.sedmelluq.discord.lavaplayer.track.TrackStateListener;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameBuffer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.TrackCandidate;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GuildMusicManagerTest {

  private static final URI ANY_URI = URI.create("https://youtube.com/watch?v=dQw4w9WgXcQ");

  private static TrackCandidate candidate(String title) {
    return new TrackCandidate(
        Source.YOUTUBE, title, "artist", Duration.ofSeconds(200), ANY_URI, 0.0, new StubTrack(title));
  }

  private static final class StubTrack implements InternalAudioTrack {
    private final AudioTrackInfo info;
    private volatile AudioTrackExecutor activeExecutor;

    StubTrack(String title) {
      this.info =
          new AudioTrackInfo(title, "artist", 200_000L, title, false, "https://youtube.com/watch");
    }

    @Override
    public AudioTrackInfo getInfo() {
      return info;
    }

    @Override
    public String getIdentifier() {
      return info.identifier;
    }

    @Override
    public AudioTrackState getState() {
      return activeExecutor == null ? AudioTrackState.INACTIVE : activeExecutor.getState();
    }

    @Override
    public void stop() {
      if (activeExecutor != null) {
        activeExecutor.stop();
      }
    }

    @Override
    public boolean isSeekable() {
      return false;
    }

    @Override
    public long getPosition() {
      return 0;
    }

    @Override
    public void setPosition(long position) {
    }

    @Override
    public void setMarker(TrackMarker marker) {
    }

    @Override
    public void addMarker(TrackMarker marker) {
    }

    @Override
    public void removeMarker(TrackMarker marker) {
    }

    @Override
    public long getDuration() {
      return info.length;
    }

    @Override
    public AudioTrack makeClone() {
      return new StubTrack(info.title);
    }

    @Override
    public AudioSourceManager getSourceManager() {
      return null;
    }

    @Override
    public void setUserData(Object userData) {
    }

    @Override
    public Object getUserData() {
      return null;
    }

    @Override
    public <T> T getUserData(Class<T> klass) {
      return null;
    }

    @Override
    public void assignExecutor(AudioTrackExecutor executor, boolean applyPrimordialState) {
      this.activeExecutor = executor;
    }

    @Override
    public AudioTrackExecutor getActiveExecutor() {
      return activeExecutor;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) {
    }

    @Override
    public AudioTrackExecutor createLocalExecutor(AudioPlayerManager manager) {
      return new NoopExecutor();
    }

    @Override
    public AudioFrame provide() {
      return null;
    }

    @Override
    public AudioFrame provide(long timeout, TimeUnit unit) {
      return null;
    }

    @Override
    public boolean provide(MutableAudioFrame target) {
      return false;
    }

    @Override
    public boolean provide(MutableAudioFrame target, long timeout, TimeUnit unit) {
      return false;
    }
  }

  private static final class NoopExecutor implements AudioTrackExecutor {
    @Override
    public AudioFrameBuffer getAudioBuffer() {
      return null;
    }

    @Override
    public void execute(TrackStateListener listener) {
    }

    @Override
    public void stop() {
    }

    @Override
    public long getPosition() {
      return 0;
    }

    @Override
    public void setPosition(long position) {
    }

    @Override
    public AudioTrackState getState() {
      return AudioTrackState.PLAYING;
    }

    @Override
    public void setMarker(TrackMarker marker) {
    }

    @Override
    public void addMarker(TrackMarker marker) {
    }

    @Override
    public void removeMarker(TrackMarker marker) {
    }

    @Override
    public boolean failedBeforeLoad() {
      return false;
    }

    @Override
    public AudioFrame provide() {
      return null;
    }

    @Override
    public AudioFrame provide(long timeout, TimeUnit unit) {
      return null;
    }

    @Override
    public boolean provide(MutableAudioFrame target) {
      return false;
    }

    @Override
    public boolean provide(MutableAudioFrame target, long timeout, TimeUnit unit) {
      return false;
    }
  }

  private static GuildMusicManager manager(int queueMax) {
    AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    return new GuildMusicManager(playerManager, queueMax);
  }

  @Test
  void enqueueAllKeepsOrderAndReturnsCount() {
    GuildMusicManager manager = manager(200);

    int accepted = manager.enqueueAll(List.of(candidate("a"), candidate("b"), candidate("c")));

    assertEquals(3, accepted);
    assertEquals("a", manager.scheduler().current().orElseThrow().title());
    assertEquals(
        List.of("b", "c"),
        manager.scheduler().queueView().snapshot().stream().map(TrackCandidate::title).toList());
  }

  @Test
  void enqueueAllStopsWhenFull() {
    GuildMusicManager manager = manager(2);

    int accepted =
        manager.enqueueAll(
            List.of(
                candidate("a"),
                candidate("b"),
                candidate("c"),
                candidate("d"),
                candidate("e")));

    assertEquals(3, accepted);
    assertEquals(2, manager.scheduler().queueView().size());
  }

  @Test
  void enqueueAssignsUniqueIdentityAndOwner() {
    GuildMusicManager manager = manager(200);

    manager.enqueueAll(List.of(candidate("a"), candidate("b")), 42L);

    TrackCandidate current = manager.scheduler().current().orElseThrow();
    TrackCandidate queued = manager.scheduler().queueView().snapshot().get(0);
    assertEquals(42L, current.addedBy());
    assertEquals(42L, queued.addedBy());
    assertTrue(current.itemId() > 0);
    assertTrue(queued.itemId() > 0);
    assertNotEquals(current.itemId(), queued.itemId());
  }

  @Test
  void enqueueWithoutOwnerKeepsLegacyUnknown() {
    GuildMusicManager manager = manager(200);

    manager.enqueueAll(List.of(candidate("a")));

    assertEquals(0L, manager.scheduler().current().orElseThrow().addedBy());
  }

  @Test
  void skipAdvancesToNextCandidate() {
    GuildMusicManager manager = manager(200);
    manager.enqueueAll(List.of(candidate("a"), candidate("b")));

    manager.scheduler().nextTrack();

    assertEquals("b", manager.scheduler().current().orElseThrow().title());
    assertTrue(manager.scheduler().queueView().snapshot().isEmpty());
  }

  @Test
  void concurrentBatchesDoNotInterleave() throws Exception {
    GuildMusicManager manager = manager(200);
    List<TrackCandidate> batchA = new ArrayList<>();
    List<TrackCandidate> batchB = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      batchA.add(candidate("a-" + i));
      batchB.add(candidate("b-" + i));
    }
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);
    AtomicInteger acceptedA = new AtomicInteger();
    AtomicInteger acceptedB = new AtomicInteger();
    Thread threadA =
        new Thread(
            () -> {
              try {
                await(start);
                acceptedA.set(manager.enqueueAll(batchA));
              } finally {
                done.countDown();
              }
            });
    Thread threadB =
        new Thread(
            () -> {
              try {
                await(start);
                acceptedB.set(manager.enqueueAll(batchB));
              } finally {
                done.countDown();
              }
            });
    threadA.start();
    threadB.start();
    start.countDown();
    done.await();

    assertEquals(50, acceptedA.get());
    assertEquals(50, acceptedB.get());
    List<String> combined = new ArrayList<>();
    manager.scheduler().current().map(TrackCandidate::title).ifPresent(combined::add);
    manager.scheduler().queueView().snapshot().stream()
        .map(TrackCandidate::title)
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
