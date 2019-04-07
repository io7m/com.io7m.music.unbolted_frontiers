package com.io7m.unbolted_frontiers;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.io7m.jnoisetype.api.NTGenerators;
import com.io7m.jnoisetype.api.NTGenericAmount;
import com.io7m.jnoisetype.api.NTInfo;
import com.io7m.jnoisetype.api.NTLongString;
import com.io7m.jnoisetype.api.NTPitch;
import com.io7m.jnoisetype.api.NTShortString;
import com.io7m.jnoisetype.api.NTTransforms;
import com.io7m.jnoisetype.api.NTVersion;
import com.io7m.jnoisetype.writer.api.NTBuilderProviderType;
import com.io7m.jnoisetype.writer.api.NTWriteException;
import com.io7m.jnoisetype.writer.api.NTWriterProviderType;
import com.io7m.jsamplebuffer.api.SampleBufferType;
import com.io7m.jsamplebuffer.vanilla.SampleBufferDouble;
import com.io7m.jsamplebuffer.xmedia.SampleBufferXMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public final class MakeFont
{
  private static final Logger LOG = LoggerFactory.getLogger(MakeFont.class);

  private MakeFont()
  {

  }

  public static void main(
    final String[] args)
    throws IOException, NTWriteException
  {
    final var parameters = new Parameters();

    JCommander.newBuilder()
      .addObject(parameters)
      .build()
      .parse(args);

    final var pitched_sources =
      Files.list(parameters.inputDirectory)
        .sorted()
        .map(path -> {
          try {
            LOG.debug("opening source: {}", path);
            return SourceFile.open(path);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          } catch (UnsupportedAudioFileException e) {
            throw new UncheckedIOException(new IOException(e));
          }
        }).collect(Collectors.toList());

    final var percussion_sources =
      Files.list(parameters.inputPercussionDirectory)
        .sorted()
        .map(path -> {
          try {
            LOG.debug("opening source: {}", path);
            return SourceFile.open(path);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          } catch (UnsupportedAudioFileException e) {
            throw new UncheckedIOException(new IOException(e));
          }
        }).collect(Collectors.toList());

    final var adjustment_map =
      loadAdjustmentMap();

    final var builders =
      ServiceLoader.load(NTBuilderProviderType.class)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No builder service available"));

    final var writers =
      ServiceLoader.load(NTWriterProviderType.class)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No writer service available"));

    final var builder = builders.createBuilder();
    builder.setInfo(
      NTInfo.builder()
        .setName(NTShortString.of(fontName()))
        .setVersion(NTVersion.of(2, 11))
        .setProduct(NTShortString.of("com.io7m.music.unbolted_frontiers"))
        .setEngineers(NTShortString.of("Mark Raynsford <audio@io7m.com>"))
        .setCopyright(NTShortString.of("Public Domain"))
        .setCreationDate(NTShortString.of(OffsetDateTime.now().toString()))
        .setComment(NTLongString.of(textResource("comment.txt")))
        .build());

    var instrument_index = 0;
    for (; instrument_index < pitched_sources.size(); ++instrument_index) {
      final var source =
        pitched_sources.get(instrument_index);
      final var instrument =
        PitchedInstrument.create(source, instrument_index);

      final var adjustment =
        adjustment_map.getOrDefault(
          Integer.valueOf(instrument_index),
          Integer.valueOf(0))
          .intValue();

      final var sf_instrument =
        builder.addInstrument(String.format("%03d", Integer.valueOf(instrument_index)));

      final var preset =
        builder.addPreset(sf_instrument.name().value());

      final var preset_zone_global =
        preset.addZone()
          .addKeyRangeGenerator(0, 127)
          .addInstrumentGenerator(sf_instrument);

      final var instrument_zone_global =
        sf_instrument.addZone();

      instrument_zone_global.addGenerator(
        NTGenerators.findForName("coarseTune")
          .orElseThrow(() -> new IllegalStateException("Missing generator")),
        NTGenericAmount.of(((char) adjustment)));

      instrument_zone_global.addModulator(
        526,
        NTGenerators.findForName("coarseTune")
          .orElseThrow(() -> new IllegalStateException("Missing generator")),
        (short) 10,
        512,
        NTTransforms.find(0));

      for (final var note : instrument.notes) {
        final var is_first =
          instrument.notes.indexOf(note) == 0;
        final var is_last =
          instrument.notes.indexOf(note) == instrument.notes.size() - 1;

        final var sample_attack =
          builder.addSample(
            String.format(
              "%03d_%03d_A",
              Integer.valueOf(note.instrument),
              Integer.valueOf(note.rootNote)));

        sample_attack.setSampleRate((int) note.data_attack.sampleRate());
        sample_attack.setPitchCorrection(0);
        sample_attack.setSampleCount(note.data_attack.samples());
        sample_attack.setOriginalPitch(NTPitch.of(note.rootNote));
        sample_attack.setLoopStart(0L);
        sample_attack.setLoopEnd(note.data_attack.samples() - 1L);
        sample_attack.setDataWriter(channel -> copySampleToChannel(note.data_attack, channel));

        final var sample_sustain =
          builder.addSample(
            String.format(
              "%03d_%03d_S",
              Integer.valueOf(note.instrument),
              Integer.valueOf(note.rootNote)));

        sample_sustain.setSampleRate((int) note.data_sustain.sampleRate());
        sample_sustain.setPitchCorrection(0);
        sample_sustain.setSampleCount(note.data_sustain.samples());
        sample_sustain.setOriginalPitch(NTPitch.of(note.rootNote));
        sample_sustain.setLoopStart(0L);
        sample_sustain.setLoopEnd(note.data_sustain.samples() - 1L);
        sample_sustain.setDataWriter(channel -> copySampleToChannel(note.data_sustain, channel));

        final var lower_note =
          is_first ? 0 : note.rootNote;
        final var upper_note =
          is_last ? 127 : note.rootNote + 11;

        final var instrument_zone_attack = sf_instrument.addZone();
        instrument_zone_attack.addKeyRangeGenerator(lower_note, upper_note);
        instrument_zone_attack.addGenerator(
          NTGenerators.findForName("sampleModes").orElseThrow(),
          NTGenericAmount.of(0));
        instrument_zone_attack.addSampleGenerator(sample_attack);

        final var instrument_zone_sustain = sf_instrument.addZone();
        instrument_zone_sustain.addKeyRangeGenerator(lower_note, upper_note);
        instrument_zone_sustain.addGenerator(
          NTGenerators.findForName("sampleModes").orElseThrow(),
          NTGenericAmount.of(1));
        instrument_zone_sustain.addSampleGenerator(sample_sustain);
      }
    }

    {
      final var instrument =
        PercussiveInstrument.create(percussion_sources, instrument_index);

      final var sf_instrument =
        builder.addInstrument(String.format("p_%03d", Integer.valueOf(instrument_index)));

      final var preset =
        builder.addPreset(sf_instrument.name().value());

      preset.setBank(128);

      final var preset_zone_global =
        preset.addZone()
          .addKeyRangeGenerator(0, 127)
          .addInstrumentGenerator(sf_instrument);

      final var instrument_zone_global =
        sf_instrument.addZone();

      instrument_zone_global.addModulator(
        526,
        NTGenerators.findForName("coarseTune")
          .orElseThrow(() -> new IllegalStateException("Missing generator")),
        (short) 10,
        512,
        NTTransforms.find(0));

      for (final var note : instrument.notes) {
        final var sample_attack =
          builder.addSample(
            String.format(
              "p_%03d_%03d_A",
              Integer.valueOf(note.instrument),
              Integer.valueOf(note.rootNote)));

        sample_attack.setSampleRate((int) note.data_attack.sampleRate());
        sample_attack.setPitchCorrection(0);
        sample_attack.setSampleCount(note.data_attack.samples());
        sample_attack.setOriginalPitch(NTPitch.of(note.rootNote));
        sample_attack.setLoopStart(0L);
        sample_attack.setLoopEnd(note.data_attack.samples() - 1L);
        sample_attack.setDataWriter(channel -> copySampleToChannel(note.data_attack, channel));

        final var sample_sustain =
          builder.addSample(
            String.format(
              "p_%03d_%03d_S",
              Integer.valueOf(note.instrument),
              Integer.valueOf(note.rootNote)));

        sample_sustain.setSampleRate((int) note.data_sustain.sampleRate());
        sample_sustain.setPitchCorrection(0);
        sample_sustain.setSampleCount(note.data_sustain.samples());
        sample_sustain.setOriginalPitch(NTPitch.of(note.rootNote));
        sample_sustain.setLoopStart(0L);
        sample_sustain.setLoopEnd(note.data_sustain.samples() - 1L);
        sample_sustain.setDataWriter(channel -> copySampleToChannel(note.data_sustain, channel));

        final var instrument_zone_attack = sf_instrument.addZone();
        instrument_zone_attack.addKeyRangeGenerator(note.rootNote, note.rootNote);
        instrument_zone_attack.addGenerator(
          NTGenerators.findForName("sampleModes").orElseThrow(),
          NTGenericAmount.of(0));
        instrument_zone_attack.addSampleGenerator(sample_attack);

        final var instrument_zone_sustain = sf_instrument.addZone();
        instrument_zone_sustain.addKeyRangeGenerator(note.rootNote, note.rootNote);
        instrument_zone_sustain.addGenerator(
          NTGenerators.findForName("sampleModes").orElseThrow(),
          NTGenericAmount.of(1));
        instrument_zone_sustain.addSampleGenerator(sample_sustain);
      }
    }

    final var description = builder.build();
    try (var channel = FileChannel.open(parameters.outputFile, CREATE, TRUNCATE_EXISTING, WRITE)) {
      final var writer = writers.createForChannel(
        parameters.outputFile.toUri(),
        description,
        channel);
      writer.write();
    }
  }

  private static Map<Integer, Integer> loadAdjustmentMap()
    throws IOException
  {
    final var map = new HashMap<Integer, Integer>(128);
    try (var stream = MakeFont.class.getResourceAsStream(
      "/com/io7m/unbolted_frontiers/pitch_adjust.properties")) {

      final var properties = new Properties();
      properties.load(stream);

      for (final var entry : properties.entrySet()) {
        final var key = ((String) entry.getKey()).trim();
        final var val = ((String) entry.getValue()).trim();
        map.put(
          Integer.valueOf(Integer.parseInt(key)),
          Integer.valueOf(Integer.parseInt(val)));
      }
    }
    return map;
  }

  private static String textResource(
    final String name)
    throws IOException
  {
    final var path = "/com/io7m/unbolted_frontiers/" + name;
    try (var stream = MakeFont.class.getResourceAsStream(path)) {
      return new String(stream.readAllBytes(), US_ASCII);
    }
  }

  private static String fontName()
  {
    final var ver = fontVersion();
    return "Unbolted Frontiers " + ver;
  }

  private static String fontVersion()
  {
    final var pack = MakeFont.class.getPackage();
    if (pack != null) {
      final var ver = pack.getImplementationVersion();
      if (ver != null) {
        return ver;
      }
    }
    return "0.0.0";
  }

  private static void copySampleToChannel(
    final SampleBufferType source,
    final SeekableByteChannel channel)
    throws IOException
  {
    final var buffer =
      ByteBuffer.allocate(Math.toIntExact(source.samples() * 2L))
        .order(LITTLE_ENDIAN);

    for (var index = 0L; index < source.frames(); ++index) {
      final var frame_d = source.frameGetExact(index);

      /*
       * Quantize to 8-bit for worse quality.
       */

      final var frame_s = frame_d * 32767.0;
      final var frame_i = (short) frame_s;
      buffer.putShort(frame_i);
    }

    buffer.flip();
    final var wrote = channel.write(buffer);
    if (wrote != buffer.capacity()) {
      throw new IOException(
        new StringBuilder(32)
          .append("Wrote too few bytes (wrote ")
          .append(wrote)
          .append(" expected ")
          .append(buffer.capacity())
          .append(")")
          .toString());
    }
  }

  public static final class Parameters
  {
    @Parameter(names = "--input-directory", required = true)
    Path inputDirectory;

    @Parameter(names = "--input-percussion-directory", required = true)
    Path inputPercussionDirectory;

    @Parameter(names = "--output-file", required = true)
    Path outputFile;
  }

  private static final class SourceFile
  {
    private final SampleBufferType buffer;

    public SourceFile(
      final SampleBufferType in_buffer)
    {
      this.buffer = Objects.requireNonNull(in_buffer, "buffer");
    }

    public static SourceFile open(final Path file)
      throws IOException, UnsupportedAudioFileException
    {
      try (var stream = AudioSystem.getAudioInputStream(file.toFile())) {
        final var format = stream.getFormat();
        LOG.debug("format: {}", format);
        LOG.debug("available: {}", Integer.valueOf(stream.available()));

        final var frameSize = format.getChannels() * (format.getSampleSizeInBits() / 8);
        final var targetFormat = new AudioFormat(
          AudioFormat.Encoding.PCM_SIGNED,
          format.getSampleRate(),
          format.getSampleSizeInBits(),
          format.getChannels(),
          frameSize,
          format.getSampleRate(),
          false);

        try (var convertStream = AudioSystem.getAudioInputStream(targetFormat, stream)) {
          return new SourceFile(SampleBufferXMedia.sampleBufferOfStream(
            convertStream,
            (channels, frames, sample_rate) ->
              SampleBufferDouble.createWithByteBuffer(
                channels,
                frames,
                sample_rate,
                value -> ByteBuffer.allocate(Math.toIntExact(value)).order(LITTLE_ENDIAN))));
        }
      }
    }
  }

  private static final class PitchedInstrument
  {
    private final List<Note> notes;

    private PitchedInstrument(
      final List<Note> in_notes)
    {
      this.notes = Objects.requireNonNull(in_notes, "notes");
    }

    public static PitchedInstrument create(
      final SourceFile file,
      final int instrument)
    {
      final var frames = file.buffer.frames();
      final var segmentFrames = frames / 8L;
      final var notes = new ArrayList<Note>(8);

      var rootNote = 12;
      for (var segment = 0L; segment < 8L; ++segment) {
        final var buffer =
          SampleBufferDouble.createWithHeapBuffer(
            1,
            segmentFrames,
            file.buffer.sampleRate());

        final var frameData = new double[file.buffer.channels()];
        for (var frame = 0L; frame < segmentFrames; ++frame) {
          final var sourceIndex = (segment * segmentFrames) + frame;
          file.buffer.frameGetExact(sourceIndex, frameData);
          buffer.frameSetExact(frame, frameData[0]);
        }

        notes.add(Note.create(buffer, instrument, rootNote));
        rootNote += 12;
      }

      return new PitchedInstrument(notes);
    }
  }

  private static final class PercussiveInstrument
  {
    private final List<Note> notes;

    private PercussiveInstrument(
      final List<Note> in_notes)
    {
      this.notes = Objects.requireNonNull(in_notes, "notes");
    }

    public static PercussiveInstrument create(
      final List<SourceFile> files,
      final int instrument)
    {
      final var notes = new ArrayList<Note>(files.size());

      var rootNote = 36;
      for (final var file : files) {
        notes.add(Note.create(file.buffer, instrument, rootNote));
        rootNote += 1;
      }

      return new PercussiveInstrument(notes);
    }
  }

  private static final class Note
  {
    private final SampleBufferType data_attack;
    private final SampleBufferType data_sustain;
    private final int instrument;
    private final int rootNote;

    Note(
      final SampleBufferType data_attack,
      final SampleBufferType data_sustain,
      final int instrument,
      final int rootNote)
    {
      this.data_attack = Objects.requireNonNull(data_attack, "data_attack");
      this.data_sustain = Objects.requireNonNull(data_sustain, "data_sustain");
      this.instrument = instrument;
      this.rootNote = rootNote;
    }

    public static Note create(
      final SampleBufferType data,
      final int instrument,
      final int rootNote)
    {
      final var sus = createNoteWithSustain(data);
      createNoteWithAttack(data);
      return new Note(data, sus, instrument, rootNote);
    }

    private static void fadeOut(
      final SampleBufferType input,
      final long fadeStart,
      final long fadeEnd)
    {
      final var frameData = new double[input.channels()];

      for (var frame = fadeStart; frame < fadeEnd; ++frame) {
        final var position =
          ((double) frame - (double) fadeStart) / ((double) fadeEnd - (double) fadeStart);

        input.frameGetExact(frame, frameData);
        final var amp = 1.0 - position;
        for (var channel = 0; channel < frameData.length; ++channel) {
          frameData[channel] = frameData[channel] * amp;
        }
        input.frameSetExact(frame, frameData);
      }
    }

    private static void fadeIn(
      final SampleBufferType input,
      final long fadeStart,
      final long fadeEnd)
    {
      final var frameData = new double[input.channels()];

      for (var frame = fadeStart; frame < fadeEnd; ++frame) {
        final var position =
          ((double) frame - (double) fadeStart) / ((double) fadeEnd - (double) fadeStart);

        input.frameGetExact(frame, frameData);
        for (var channel = 0; channel < frameData.length; ++channel) {
          frameData[channel] = frameData[channel] * position;
        }
        input.frameSetExact(frame, frameData);
      }
    }

    private static void createNoteWithAttack(
      final SampleBufferType data)
    {
      fadeOut(data, 0L, data.frames());
    }

    private static SampleBufferType createNoteWithSustain(
      final SampleBufferType input)
    {
      final var frameData0 = new double[input.channels()];
      final var frameData1 = new double[input.channels()];
      final var frameData2 = new double[input.channels()];

      final var frameCount = input.frames();
      final var frameCountHalf = frameCount / 2L;

      final var bufferSustainOutput =
        SampleBufferDouble.createWithHeapBuffer(
          input.channels(),
          frameCount,
          input.sampleRate());

      final var bufferSustainForwards =
        SampleBufferDouble.createWithHeapBuffer(
          input.channels(),
          frameCountHalf,
          input.sampleRate());

      /*
       * Copy the second half of the sample.
       */

      for (var frame = 0L; frame < frameCountHalf; ++frame) {
        input.frameGetExact(frame + frameCountHalf, frameData0);
        bufferSustainForwards.frameSetExact(frame, frameData0);
      }

      /*
       * Fade in and fade out the copied half.
       */

      final var frameCountQuarter = frameCountHalf / 2L;
      fadeIn(bufferSustainForwards, 0L, frameCountQuarter);
      fadeOut(bufferSustainForwards, frameCountQuarter, frameCountHalf);

      /*
       * Now, mix together copies of the faded half, offset by half the length of the half-length
       * sample.
       */

      for (var frame = 0L; frame < frameCount; ++frame) {
        final var inputFrame = frame % frameCountHalf;
        final var inputFrameShift = (frame + frameCountQuarter) % frameCountHalf;

        bufferSustainForwards.frameGetExact(inputFrame, frameData0);
        bufferSustainForwards.frameGetExact(inputFrameShift, frameData1);

        for (var channel = 0; channel < frameData2.length; ++channel) {
          frameData2[channel] = (frameData0[channel] + frameData1[channel]);
        }

        bufferSustainOutput.frameSetExact(frame, frameData2);
      }

      return bufferSustainOutput;
    }
  }
}
