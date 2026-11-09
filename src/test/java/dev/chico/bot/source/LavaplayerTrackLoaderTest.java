package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import java.io.DataInput;
import java.io.DataOutput;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class LavaplayerTrackLoaderTest {

  private static final URI ANY_URI = URI.create("https://youtube.com/watch?v=dQw4w9WgXcQ");

  private static AudioPlayerManager managerWith(AudioSourceManager source) {
    DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
    manager.registerSourceManager(source);
    return manager;
  }

  private static class NullSource implements AudioSourceManager {
    @Override
    public String getSourceName() {
      return "null-source";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
      return null;
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
      return false;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {
    }
  }

  private static final class FailingSource extends NullSource {
    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
      throw new FriendlyException("source down", FriendlyException.Severity.FAULT, null);
    }
  }

  private static final class EmptyPlaylistSource extends NullSource {
    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
      return new com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist(
          "empty", List.of(), null, false);
    }
  }

  @Test
  void emptyPlaylistGivesEmptyList() throws Exception {
    LavaplayerTrackLoader loader =
        new LavaplayerTrackLoader(
            Source.YOUTUBE, managerWith(new EmptyPlaylistSource()), 5, Duration.ofSeconds(5));

    assertTrue(loader.loadUrl(ANY_URI).get().isEmpty());
  }

  @Test
  void noMatchesGivesEmptyList() throws Exception {
    LavaplayerTrackLoader loader =
        new LavaplayerTrackLoader(
            Source.YOUTUBE, managerWith(new NullSource()), 5, Duration.ofSeconds(5));

    assertTrue(loader.search("ytsearch:whatever").get().isEmpty());
    assertTrue(loader.loadUrl(ANY_URI).get().isEmpty());
  }

  @Test
  void loadFailureCompletesExceptionally() {
    LavaplayerTrackLoader loader =
        new LavaplayerTrackLoader(
            Source.YOUTUBE, managerWith(new FailingSource()), 5, Duration.ofSeconds(5));

    assertThrows(ExecutionException.class, () -> loader.search("ytsearch:whatever").get());
    assertThrows(ExecutionException.class, () -> loader.loadUrl(ANY_URI).get());
  }

  @Test
  void loaderReportsItsSource() {
    LavaplayerTrackLoader loader =
        new LavaplayerTrackLoader(
            Source.SOUNDCLOUD, managerWith(new NullSource()), 5, Duration.ofSeconds(5));

    assertEquals(Source.SOUNDCLOUD, loader.source());
  }

  @Test
  @Tag("integration")
  void youtubeSearchAndUrlLoad() throws Exception {
    DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
    manager.registerSourceManager(new dev.lavalink.youtube.YoutubeAudioSourceManager());
    LavaplayerTrackLoader loader =
        new LavaplayerTrackLoader(Source.YOUTUBE, manager, 5, Duration.ofSeconds(15));

    List<TrackCandidate> results = loader.search("ytsearch:Tim Maia Azul da Cor do Mar").get();
    assertTrue(results.size() >= 1 && results.size() <= 5);
    assertTrue(results.get(0).track() != null);

    List<TrackCandidate> direct =
        loader.loadUrl(URI.create("https://www.youtube.com/watch?v=s3a4OQR-10M")).get();
    assertEquals(1, direct.size());
    manager.shutdown();
  }

  @Test
  @Tag("integration")
  void soundCloudSearchLoads() throws Exception {
    // POR QUE: SoundCloud via Lavaplayer e instavel no upstream (a busca pode
    // falhar com "Something went wrong when looking up the track" quando a API
    // muda o formato). O teste sonda a saude da fonte: se o upstream responde,
    // valida o formato; se nao, aborta documentando, sem quebrar a suite.
    DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
    manager.registerSourceManager(
        com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
            .createDefault());
    LavaplayerTrackLoader loader =
        new LavaplayerTrackLoader(Source.SOUNDCLOUD, manager, 5, Duration.ofSeconds(15));

    List<TrackCandidate> results;
    try {
      results = loader.search("scsearch:Tim Maia Azul da Cor do Mar").get();
    } catch (java.util.concurrent.ExecutionException e) {
      org.junit.jupiter.api.Assumptions.abort(
          "SoundCloud upstream unavailable: " + e.getCause());
      return;
    } finally {
      manager.shutdown();
    }
    assertTrue(results.size() >= 1 && results.size() <= 5);
    assertTrue(results.get(0).track() != null);
  }
}
