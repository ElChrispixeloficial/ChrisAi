#include <jni.h>
#include <android/bitmap.h>

#include <cstdint>
#include <cstring>
#include <cstdlib>

#include <vector>
#include <string>

#include <fcntl.h>
#include <unistd.h>

// ---------------------------------------------------------------------------
// ChrisAI native core (chriscore).
//  - ChaCha20 encryption (crypto for the API key at rest).
//  - SSE delta extraction (hot path of the chat stream).
//  - Procedural aurora rendering (native graphics).
// No third-party dependencies; the key material lives only inside this .so.
// ---------------------------------------------------------------------------

static inline uint32_t load32(const uint8_t* p) {
    return (uint32_t)p[0] |
           ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

static inline void store32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xFF);
    p[1] = (uint8_t)((v >> 8) & 0xFF);
    p[2] = (uint8_t)((v >> 16) & 0xFF);
    p[3] = (uint8_t)((v >> 24) & 0xFF);
}

#define ROTL32(v, n) (((v) << (n)) | ((v) >> (32 - (n))))
#define QR(a, b, c, d)                                                     \
    do {                                                                   \
        (a) += (b);                                                        \
        (d) ^= (a);                                                        \
        (d) = ROTL32((d), 16);                                             \
        (c) += (d);                                                        \
        (b) ^= (c);                                                        \
        (b) = ROTL32((b), 12);                                             \
        (a) += (b);                                                        \
        (d) ^= (a);                                                        \
        (d) = ROTL32((d), 8);                                              \
        (c) += (d);                                                        \
        (b) ^= (c);                                                        \
        (b) = ROTL32((b), 7);                                              \
    } while (0)

// Derives the 256-bit key inside native code (never exposed to Java/Kotlin).
static void derive_key(uint8_t out[32]) {
    const char* seed = "ChrisAI.vault.2026.nativo";
    uint32_t h = 0x811c9dc5u;
    uint8_t tmp[32];
    size_t n = strlen(seed);
    for (size_t i = 0; i < 32; i++) tmp[i] = (uint8_t)seed[i % n];
    for (int round = 0; round < 8; round++) {
        for (size_t i = 0; i < 32; i++) {
            tmp[i] = (uint8_t)(tmp[i] ^ h);
            h = h * 31u + tmp[i];
            tmp[i] = (uint8_t)(
                (tmp[i] * 131u + h) ^ (h >> 16));
            for (int b = 0; b < 3; b++)
                tmp[i] = (uint8_t)((tmp[i] << 3) | (tmp[i] >> 5));
        }
    }
    memcpy(out, tmp, 32);
}

static void chacha20_block(const uint8_t key[32], const uint8_t nonce[12],
                           uint32_t counter, uint8_t out[64]) {
    uint32_t state[16];
    state[0] = 0x61707865u;
    state[1] = 0x3320646eu;
    state[2] = 0x79622d32u;
    state[3] = 0x6b206574u;
    for (int i = 0; i < 8; i++) state[4 + i] = load32(key + i * 4);
    state[12] = counter;
    state[13] = load32(nonce);
    state[14] = load32(nonce + 4);
    state[15] = load32(nonce + 8);

    uint32_t x[16];
    memcpy(x, state, sizeof(state));
    for (int i = 0; i < 10; i++) {
        QR(x[0], x[4], x[8], x[12]);
        QR(x[1], x[5], x[9], x[13]);
        QR(x[2], x[6], x[10], x[14]);
        QR(x[3], x[7], x[11], x[15]);
        QR(x[0], x[5], x[10], x[15]);
        QR(x[1], x[6], x[11], x[12]);
        QR(x[2], x[7], x[8], x[13]);
        QR(x[3], x[4], x[9], x[14]);
    }
    for (int i = 0; i < 16; i++)
        store32(out + i * 4, x[i] + state[i]);
}

static void chacha20_xor(const uint8_t* in, size_t len, const uint8_t key[32],
                         const uint8_t nonce[12], uint32_t counter, uint8_t* out) {
    uint8_t block[64];
    size_t offset = 0;
    while (len > 0) {
        chacha20_block(key, nonce, counter, block);
        counter++;
        size_t take = len < 64 ? len : 64;
        for (size_t j = 0; j < take; j++) out[offset + j] = in[offset + j] ^ block[j];
        offset += take;
        len -= take;
    }
}

static void fill_nonce(uint8_t nonce[12]) {
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd >= 0) {
        ssize_t r = read(fd, nonce, 12);
        (void)r;
        close(fd);
        return;
    }
    for (int i = 0; i < 12; i++) nonce[i] = (uint8_t)(0x5a + i * 7);
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_chrispixel_chrisai_nativebridge_NativeBridge_encrypt(JNIEnv* env,
                                                              jobject /*thiz*/,
                                                              jbyteArray input) {
    if (input == nullptr) return nullptr;
    jsize inLen = env->GetArrayLength(input);
    if (inLen <= 0) return nullptr;
    jbyte* in = env->GetByteArrayElements(input, nullptr);
    if (in == nullptr) return nullptr;

    uint8_t key[32];
    derive_key(key);
    uint8_t nonce[12];
    fill_nonce(nonce);

    std::vector<uint8_t> cipher((size_t)inLen);
    chacha20_xor(reinterpret_cast<const uint8_t*>(in), (size_t)inLen,
                 key, nonce, 0, cipher.data());
    env->ReleaseByteArrayElements(input, in, JNI_ABORT);

    std::vector<uint8_t> result(12 + (size_t)inLen);
    memcpy(result.data(), nonce, 12);
    memcpy(result.data() + 12, cipher.data(), (size_t)inLen);

    jbyteArray arr = env->NewByteArray((jsize)result.size());
    if (arr != nullptr)
        env->SetByteArrayRegion(arr, 0, (jsize)result.size(),
                                reinterpret_cast<const jbyte*>(result.data()));
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_chrispixel_chrisai_nativebridge_NativeBridge_decrypt(JNIEnv* env,
                                                              jobject /*thiz*/,
                                                              jbyteArray input) {
    if (input == nullptr) return nullptr;
    jsize total = env->GetArrayLength(input);
    if (total <= 12) {
        jbyteArray empty = env->NewByteArray(0);
        return empty;
    }
    jbyte* raw = env->GetByteArrayElements(input, nullptr);
    if (raw == nullptr) return nullptr;

    const uint8_t* blob = reinterpret_cast<const uint8_t*>(raw);
    const uint8_t* nonce = blob;
    const uint8_t* cipher = blob + 12;
    size_t cipherLen = (size_t)(total - 12);

    uint8_t key[32];
    derive_key(key);

    std::vector<uint8_t> plain(cipherLen);
    chacha20_xor(cipher, cipherLen, key, nonce, 0, plain.data());
    env->ReleaseByteArrayElements(input, raw, JNI_ABORT);

    jbyteArray arr = env->NewByteArray((jsize)plain.size());
    if (arr != nullptr)
        env->SetByteArrayRegion(arr, 0, (jsize)plain.size(),
                                reinterpret_cast<const jbyte*>(plain.data()));
    return arr;
}

// Extracts the `content` delta from a raw SSE JSON line. Returns the UTF-8
// bytes of the delta, or null when the line carries no content.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_chrispixel_chrisai_nativebridge_NativeBridge_extractDelta(JNIEnv* env,
                                                                   jobject /*thiz*/,
                                                                   jstring line) {
    if (line == nullptr) return nullptr;
    const char* utf = env->GetStringUTFChars(line, nullptr);
    if (utf == nullptr) return nullptr;

    const char* marker = "\"content\":\"";
    const char* p = strstr(utf, marker);
    jbyteArray out = nullptr;

    if (p != nullptr) {
        p += strlen(marker);
        std::vector<uint8_t> buffer;
        const char* q = p;
        while (*q) {
            char c = *q;
            if (c == '"') break;
            if (c == '\\') {
                q++;
                if (!*q) break;
                switch (*q) {
                    case 'n': buffer.push_back('\n'); q++; break;
                    case 'r': buffer.push_back('\r'); q++; break;
                    case 't': buffer.push_back('\t'); q++; break;
                    case '"': buffer.push_back('"');  q++; break;
                    case '\\': buffer.push_back('\\'); q++; break;
                    case 'u': {
                        unsigned cp = 0;
                        int ok = 1;
                        for (int i = 0; i < 4; i++) {
                            q++;
                            char h = *q;
                            cp <<= 4;
                            if (h >= '0' && h <= '9') cp |= (unsigned)(h - '0');
                            else if (h >= 'a' && h <= 'f') cp |= (unsigned)(h - 'a' + 10);
                            else if (h >= 'A' && h <= 'F') cp |= (unsigned)(h - 'A' + 10);
                            else ok = 0;
                        }
                        q++;
                        if (!ok) break;
                        if (cp < 0x80) {
                            buffer.push_back((uint8_t)cp);
                        } else if (cp < 0x800) {
                            buffer.push_back((uint8_t)(0xC0 | (cp >> 6)));
                            buffer.push_back((uint8_t)(0x80 | (cp & 0x3F)));
                        } else {
                            buffer.push_back((uint8_t)(0xE0 | (cp >> 12)));
                            buffer.push_back((uint8_t)(0x80 | ((cp >> 6) & 0x3F)));
                            buffer.push_back((uint8_t)(0x80 | (cp & 0x3F)));
                        }
                        break;
                    }
                    default:
                        buffer.push_back('\\');
                        buffer.push_back((uint8_t)*q);
                        q++;
                        break;
                }
            } else {
                buffer.push_back((uint8_t)c);
                q++;
            }
        }
        if (!buffer.empty()) {
            out = env->NewByteArray((jsize)buffer.size());
            if (out != nullptr)
                env->SetByteArrayRegion(out, 0, (jsize)buffer.size(),
                                        reinterpret_cast<const jbyte*>(buffer.data()));
        }
    }

    env->ReleaseStringUTFChars(line, utf);
    return out;
}

// Procedurally fills an ARGB_8888 bitmap with a smooth dark aurora.
extern "C" JNIEXPORT void JNICALL
Java_com_chrispixel_chrisai_nativebridge_NativeBridge_fillAurora(JNIEnv* env,
                                                                 jobject /*thiz*/,
                                                                 jobject bitmap,
                                                                 jint width,
                                                                 jint height,
                                                                 jlong seed) {
    if (bitmap == nullptr || width <= 0 || height <= 0) return;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return;

    const double s1 = (double)(seed % 100000u) * 0.0001;
    const double s2 = (double)((seed / 7) % 100000u) * 0.00013;
    const size_t rowBytes = info.stride / 4;

    for (int y = 0; y < height; y++) {
        uint32_t* row = reinterpret_cast<uint32_t*>(pixels) + (size_t)y * rowBytes;
        double fy = (double)y / (double)height;
        for (int x = 0; x < width; x++) {
            double fx = (double)x / (double)width;
            double n = 0.5 + 0.5 * sin(fx * 9.0 + s1) * cos(fy * 7.0 + s2);
            double n2 = 0.5 + 0.5 * sin(fx * 13.0 + s2 + 1.7) * sin(fy * 5.0 + s1);
            double t = fy * 0.68 + n * 0.32;
            t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
            double r = 18.0 * (1.0 - t) + 11.0 * t;
            double g = 20.0 * (1.0 - t) + 42.0 * t + 6.0 * n2;
            double b = 28.0 * (1.0 - t) + 52.0 * t + 12.0 * n;
            uint32_t R = (uint32_t)(r < 0 ? 0 : (r > 255 ? 255 : r));
            uint32_t G = (uint32_t)(g < 0 ? 0 : (g > 255 ? 255 : g));
            uint32_t B = (uint32_t)(b < 0 ? 0 : (b > 255 ? 255 : b));
            row[x] = (0xFFu << 24) | (R << 16) | (G << 8) | B;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}