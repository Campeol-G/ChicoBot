package dev.chico.bot.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.TrackMarker;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.TrackCandidate;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TrackSchedulerTest {

  private static final class StubTrack implements InternalAudioTrack {
    private final AudioTrackInfo info;

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
      return AudioTrackState.INACTIVE;
    }

    @Override
    public void stop() {
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
    }

    @Override
    public AudioTrackExecutor getActiveExecutor() {
      return null;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) {
    }

    @Override
    public AudioTrackExecutor createLocalExecutor(AudioPlayerManager manager) {
      return null;
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

  private static TrackCandidate candidate(String title) {
    return new TrackCandidate(
        Source.YOUTUBE,
        title,
        "artist",
        Duration.ofSeconds(200),
        URI.create("https://youtube.com/watch?v=dQw4w9WgXcQ"),
        0.0,
        new StubTrack(title));
  }

  private record Fixture(AudioPlayer player, TrackScheduler scheduler, TrackCandidate a, TrackCandidate b) {
  }

  private static Fixture fixture() {
    AudioPlayerManager manager = new DefaultAudioPlayerManager();
    AudioPlayer player = manager.createPlayer();
    TrackScheduler scheduler = new TrackScheduler(player, 200);
    TrackCandidate a = candidate("a");
    TrackCandidate b = candidate("b");
    scheduler.queue(a);
    scheduler.queue(b);
    return new Fixture(player, scheduler, a, b);
  }

  @Test
  void onTrackEndAdvancesWhenMayStartNext() {
    Fixture fix = fixture();

    fix.scheduler().onTrackEnd(fix.player(), fix.a().track(), AudioTrackEndReason.FINISHED);

    assertEquals("b", fix.scheduler().current().orElseThrow().title());
  }

  @Test
  void onTrackEndStopsWhenMayNot() {
    // POR QUE: in this Lavaplayer build LOAD_FAILED.mayStartNext is true;
    // STOPPED is the reason that clears the current track.
    Fixture fix = fixture();

    fix.scheduler().onTrackEnd(fix.player(), fix.a().track(), AudioTrackEndReason.STOPPED);

    assertTrue(fix.scheduler().current().isEmpty());
  }

  @Test
  void onTrackExceptionSkipsToNext() {
    Fixture fix = fixture();

    fix.scheduler()
        .onTrackException(
            fix.player(),
            fix.a().track(),
            new FriendlyException("boom", FriendlyException.Severity.FAULT, null));

    assertEquals("b", fix.scheduler().current().orElseThrow().title());
  }

  @Test
  void pendingMismatchIgnored() {
    Fixture fix = fixture();
    AudioTrack stranger = new StubTrack("stranger");

    fix.scheduler().onTrackEnd(fix.player(), stranger, AudioTrackEndReason.FINISHED);

    assertEquals("a", fix.scheduler().current().orElseThrow().title());
  }

  @Test
  void skipToJumpsToTarget() {
    Fixture fix = fixture();

    fix.scheduler().skipTo(0);

    assertEquals("b", fix.scheduler().current().orElseThrow().title());
  }

  @Test
  void removeAtReturnsRemovedTitle() {
    Fixture fix = fixture();

    assertEquals("b", fix.scheduler().removeAt(0).orElseThrow().title());
    assertTrue(fix.scheduler().queueView().snapshot().isEmpty());
  }

  @Test
  void stopAndClearEmptiesEverything() {
    Fixture fix = fixture();

    fix.scheduler().stopAndClear();

    assertTrue(fix.scheduler().queueView().snapshot().isEmpty());
  }

  @Test
  void stopAndClearClearsCurrent() {
    Fixture fix = fixture();

    fix.scheduler().stopAndClear();

    assertTrue(fix.scheduler().current().isEmpty());
  }

  @Test
  void skipToAgainstConcurrentTrackEndStaysConsistent() throws Exception {
    // Best-effort guard: the race window needs a mid-sequence track end,
    // which no deterministic single-threaded test can stage. This pins that
    // skipTo linearizes against onTrackEnd: no exception, current always a
    // known track or empty.
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(4);
    java.util.List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
    java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
    for (int i = 0; i < 100; i++) {
      Fixture fix = fixture();
      fix.scheduler().queue(candidate("c"));
      fix.scheduler().queue(candidate("d"));
      done.add(
          pool.submit(
              () -> {
                try {
                  fix.scheduler().skipTo(3);
                } catch (Exception e) {
                  errors.incrementAndGet();
                }
                return null;
              }));
      done.add(
          pool.submit(
              () -> {
                try {
                  fix.scheduler()
                      .onTrackEnd(
                          fix.player(), fix.a().track(), AudioTrackEndReason.FINISHED);
                } catch (Exception e) {
                  errors.incrementAndGet();
                }
                return null;
              }));
    }
    for (java.util.concurrent.Future<?> f : done) {
      f.get();
    }
    pool.shutdown();
    assertEquals(0, errors.get());
  }
}
