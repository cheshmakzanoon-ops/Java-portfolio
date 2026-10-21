package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable bounded numeric artifact, with development-only scaler provenance and no intercept. */
public final class LinearRankingModel {
  private static final int MAGIC = 0x49464c52;
  private static final int MAX_BYTES = 8192;
  private final double[] means, scales, weights;
  private final String trainingExport, folds, identity;
  private final double l2;

  public LinearRankingModel(
      double[] means,
      double[] scales,
      double[] weights,
      String trainingExport,
      String folds,
      double l2) {
    this.means = SearchFeatureSchema.checked(means);
    if (scales == null
        || weights == null
        || scales.length != this.means.length
        || weights.length != this.means.length)
      throw new IllegalArgumentException("model dimension");
    this.scales = scales.clone();
    this.weights = weights.clone();
    for (int i = 0; i < scales.length; i++)
      if (!Double.isFinite(this.scales[i])
          || this.scales[i] < 1e-6
          || this.scales[i] > SearchFeatureSchema.LOG_MAX + 1
          || !Double.isFinite(this.weights[i])
          || Math.abs(this.weights[i]) > 1000)
        throw new IllegalArgumentException("model finite bounds");
    if (trainingExport == null
        || !trainingExport.matches("[a-f0-9]{64}")
        || folds == null
        || !folds.matches("[a-f0-9]{64}")
        || !Double.isFinite(l2)
        || l2 <= 0) throw new IllegalArgumentException("model provenance");
    this.trainingExport = trainingExport;
    this.folds = folds;
    this.l2 = l2;
    identity = Digests.sha256(payload());
  }

  public String identity() {
    return identity;
  }

  public String trainingExport() {
    return trainingExport;
  }

  public String folds() {
    return folds;
  }

  public double l2() {
    return l2;
  }

  public int dimensions() {
    return means.length;
  }

  public double[] means() {
    return means.clone();
  }

  public double[] scales() {
    return scales.clone();
  }

  public double[] weights() {
    return weights.clone();
  }

  public double[] normalized(double[] input) {
    double[] values = SearchFeatureSchema.checked(input);
    if (values.length != means.length)
      throw new IllegalArgumentException("inference model schema dimension");
    for (int i = 0; i < values.length; i++)
      values[i] = Math.clamp((values[i] - means[i]) / scales[i], -8.0, 8.0);
    return values;
  }

  public double score(double[] input) {
    double[] values = normalized(input);
    double result = 0;
    for (int i = 0; i < values.length; i++) result += values[i] * weights[i];
    if (!Double.isFinite(result)) throw new IllegalArgumentException("nonfinite model score");
    return result;
  }

  public String explanation(double[] input) {
    double[] values = normalized(input);
    StringJoiner out = new StringJoiner(" ", "linearModel=" + identity + " ", "");
    for (int i = 0; i < values.length; i++)
      if (values[i] * weights[i] != 0)
        out.add(
            SearchFeatureSchema.names(values.length).get(i)
                + "Contribution="
                + values[i] * weights[i]);
    return out.toString();
  }

  private byte[] payload() {
    try {
      var buffer = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(buffer)) {
        out.writeInt(MAGIC);
        out.writeInt(1);
        out.writeUTF(SearchFeatureSchema.version(means.length));
        out.writeInt(means.length);
        for (double[] array : List.of(means, scales, weights))
          for (double value : array) out.writeDouble(value);
        out.writeUTF(trainingExport);
        out.writeUTF(folds);
        out.writeDouble(l2);
      }
      return buffer.toByteArray();
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }

  public byte[] bytes() {
    byte[] body = payload();
    byte[] checksum = HexFormat.of().parseHex(Digests.sha256(body));
    byte[] result = Arrays.copyOf(body, body.length + checksum.length);
    System.arraycopy(checksum, 0, result, body.length, checksum.length);
    return result;
  }

  public void writeNew(Path path) throws IOException {
    Files.write(path, bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }

  public static LinearRankingModel read(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("model regular file required");
    byte[] bytes;
    try (InputStream input =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      bytes = input.readNBytes(MAX_BYTES + 1);
    }
    if (bytes.length > MAX_BYTES || bytes.length < 32) throw new IOException("model byte quota");
    byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
    if (!MessageDigest.isEqual(
        HexFormat.of().parseHex(Digests.sha256(body)),
        Arrays.copyOfRange(bytes, body.length, bytes.length)))
      throw new IOException("model checksum");
    try (var in = new DataInputStream(new ByteArrayInputStream(body))) {
      if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("model schema");
      String schema = in.readUTF();
      int dimensions = in.readInt();
      if (!schema.equals(SearchFeatureSchema.version(dimensions)))
        throw new IOException("model schema");
      double[][] arrays = new double[3][dimensions];
      for (double[] array : arrays)
        for (int i = 0; i < array.length; i++) array[i] = in.readDouble();
      var result =
          new LinearRankingModel(
              arrays[0], arrays[1], arrays[2], in.readUTF(), in.readUTF(), in.readDouble());
      if (in.read() != -1) throw new IOException("trailing model bytes");
      return result;
    } catch (IllegalArgumentException invalid) {
      throw new IOException("invalid model", invalid);
    }
  }
}
