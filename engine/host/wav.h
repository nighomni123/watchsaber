// Minimal 16-bit PCM WAV reader/writer for the host build.
//
// Host-only: the Android engine never touches the filesystem from the audio callback, and this
// file is not compiled into the shipped library at all.
#pragma once

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace lightsaber {
namespace host {

struct Wav {
  int sampleRate = 0;
  std::vector<float> samples;  // mono, normalised to -1..1

  bool ok() const { return sampleRate > 0 && !samples.empty(); }
};

inline bool readWav(const std::string& path, Wav& out, std::string& error) {
  FILE* f = std::fopen(path.c_str(), "rb");
  if (f == nullptr) {
    error = "cannot open " + path;
    return false;
  }
  unsigned char header[12];
  if (std::fread(header, 1, 12, f) != 12 || std::memcmp(header, "RIFF", 4) != 0 ||
      std::memcmp(header + 8, "WAVE", 4) != 0) {
    std::fclose(f);
    error = path + " is not a RIFF/WAVE file";
    return false;
  }

  int channels = 1;
  int bits = 16;
  std::vector<unsigned char> pcm;
  bool haveFormat = false;

  char chunkId[5] = {0};
  uint32_t chunkSize = 0;
  while (std::fread(chunkId, 1, 4, f) == 4 && std::fread(&chunkSize, 4, 1, f) == 1) {
    std::vector<unsigned char> body(chunkSize);
    if (chunkSize > 0 && std::fread(body.data(), 1, chunkSize, f) != chunkSize) break;
    if (std::memcmp(chunkId, "fmt ", 4) == 0 && chunkSize >= 16) {
      std::memcpy(&out.sampleRate, body.data() + 4, 4);
      std::memcpy(&channels, body.data() + 10, 2);
      std::memcpy(&bits, body.data() + 14, 2);
      haveFormat = true;
    } else if (std::memcmp(chunkId, "data", 4) == 0) {
      pcm = std::move(body);
    }
    if (chunkSize & 1) std::fseek(f, 1, SEEK_CUR);  // chunks are word aligned
  }
  std::fclose(f);

  if (!haveFormat) {
    error = path + " has no fmt chunk";
    return false;
  }
  if (bits != 16) {
    error = path + " is not 16-bit PCM";
    return false;
  }
  const size_t frames = pcm.size() / (2 * static_cast<size_t>(channels));
  out.samples.resize(frames);
  for (size_t i = 0; i < frames; ++i) {
    // Downmix to mono by taking the first channel; assets are authored mono.
    int16_t v;
    std::memcpy(&v, pcm.data() + i * 2 * channels, 2);
    out.samples[i] = static_cast<float>(v) / 32768.0f;
  }
  return true;
}

inline bool writeWav(const std::string& path, const std::vector<float>& samples, int sampleRate,
                     std::string& error) {
  FILE* f = std::fopen(path.c_str(), "wb");
  if (f == nullptr) {
    error = "cannot write " + path;
    return false;
  }
  // WAV is little-endian regardless of host byte order, so values are serialised byte by byte.
  auto put32 = [f](uint32_t v) {
    unsigned char b[4] = {static_cast<unsigned char>(v & 0xFF), static_cast<unsigned char>((v >> 8) & 0xFF),
                          static_cast<unsigned char>((v >> 16) & 0xFF),
                          static_cast<unsigned char>((v >> 24) & 0xFF)};
    std::fwrite(b, 1, 4, f);
  };
  auto put16 = [f](uint16_t v) {
    unsigned char b[2] = {static_cast<unsigned char>(v & 0xFF), static_cast<unsigned char>((v >> 8) & 0xFF)};
    std::fwrite(b, 1, 2, f);
  };

  const uint32_t dataBytes = static_cast<uint32_t>(samples.size() * 2);
  std::fwrite("RIFF", 1, 4, f);
  put32(36 + dataBytes);
  std::fwrite("WAVE", 1, 4, f);
  std::fwrite("fmt ", 1, 4, f);
  put32(16);
  put16(1);   // PCM
  put16(1);   // mono
  put32(static_cast<uint32_t>(sampleRate));
  put32(static_cast<uint32_t>(sampleRate * 2));  // byte rate
  put16(2);   // block align
  put16(16);  // bits per sample
  std::fwrite("data", 1, 4, f);
  put32(dataBytes);

  for (float s : samples) {
    float c = s < -1.0f ? -1.0f : (s > 1.0f ? 1.0f : s);
    put16(static_cast<uint16_t>(static_cast<int16_t>(c * 32767.0f)));
  }
  std::fclose(f);
  return true;
}

}  // namespace host
}  // namespace lightsaber