#include <jni.h>
#include <leveldb/db.h>
#include <leveldb/write_batch.h>
#include <sentencepiece_processor.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
std::mutex lock;
jlong next_id = 1;
struct Database { std::unique_ptr<leveldb::DB> db; };
struct Cursor {
  std::shared_ptr<Database> owner;
  const leveldb::Snapshot* snapshot = nullptr;
  std::unique_ptr<leveldb::Iterator> iterator;
  int endByteExclusive = 256;
  ~Cursor() { iterator.reset(); if (snapshot && owner) owner->db->ReleaseSnapshot(snapshot); }
};
std::unordered_map<jlong, std::shared_ptr<Database>> databases;
std::unordered_map<jlong, std::unique_ptr<Cursor>> cursors;
std::unordered_map<jlong, std::unique_ptr<sentencepiece::SentencePieceProcessor>> tokenizers;
void failure(JNIEnv* env, const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what()); }
void status(const leveldb::Status& s) { if (!s.ok()) throw std::runtime_error(s.ToString()); }
std::string text(JNIEnv* env, jstring input) {
  if (!input) throw std::runtime_error("Missing string");
  const jsize size = env->GetStringLength(input);
  const jchar* data = env->GetStringChars(input, nullptr);
  if (!data) throw std::runtime_error("String allocation failed");
  std::string result;
  for (jsize i = 0; i < size; ++i) {
    uint32_t cp = data[i];
    if (cp >= 0xd800 && cp <= 0xdbff) {
      if (i + 1 >= size || data[i + 1] < 0xdc00 || data[i + 1] > 0xdfff) {
        env->ReleaseStringChars(input, data); throw std::runtime_error("Invalid UTF-16");
      }
      cp = 0x10000 + ((cp - 0xd800) << 10) + (data[++i] - 0xdc00);
    } else if (cp >= 0xdc00 && cp <= 0xdfff) {
      env->ReleaseStringChars(input, data); throw std::runtime_error("Invalid UTF-16");
    }
    if (cp < 0x80) result.push_back(static_cast<char>(cp));
    else if (cp < 0x800) { result.push_back(0xc0 | (cp >> 6)); result.push_back(0x80 | (cp & 63)); }
    else if (cp < 0x10000) { result.push_back(0xe0 | (cp >> 12)); result.push_back(0x80 | ((cp >> 6) & 63)); result.push_back(0x80 | (cp & 63)); }
    else { result.push_back(0xf0 | (cp >> 18)); result.push_back(0x80 | ((cp >> 12) & 63)); result.push_back(0x80 | ((cp >> 6) & 63)); result.push_back(0x80 | (cp & 63)); }
  }
  env->ReleaseStringChars(input, data);
  return result;
}
jstring java_text(JNIEnv* env, const std::string& s) {
  std::vector<jchar> out;
  for (size_t i = 0; i < s.size();) {
    uint32_t c = static_cast<unsigned char>(s[i++]);
    int continuation = 0;
    uint32_t minimum = 0;
    if (c >= 0xf0 && c <= 0xf4) { c &= 7; continuation = 3; minimum = 0x10000; }
    else if (c >= 0xe0 && c <= 0xef) { c &= 15; continuation = 2; minimum = 0x800; }
    else if (c >= 0xc2 && c <= 0xdf) { c &= 31; continuation = 1; minimum = 0x80; }
    else if (c >= 0x80) throw std::runtime_error("Invalid UTF-8");
    for (int j = 0; j < continuation; ++j) {
      if (i >= s.size() || (static_cast<unsigned char>(s[i]) & 0xc0) != 0x80) throw std::runtime_error("Invalid UTF-8");
      c = (c << 6) | (static_cast<unsigned char>(s[i++]) & 63);
    }
    if (c < minimum || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) throw std::runtime_error("Invalid UTF-8 codepoint");
    if (c > 0xffff) { c -= 0x10000; out.push_back(0xd800 + (c >> 10)); out.push_back(0xdc00 + (c & 1023)); }
    else out.push_back(static_cast<jchar>(c));
  }
  return env->NewString(out.data(), static_cast<jsize>(out.size()));
}
std::string bytes(JNIEnv* env, jbyteArray array) {
  if (!array) throw std::runtime_error("Missing bytes");
  jsize n = env->GetArrayLength(array);
  if (n > 32 * 1024 * 1024) throw std::runtime_error("Record exceeds 32 MiB");
  std::string out(n, '\0');
  env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte*>(out.data()));
  return out;
}
jbyteArray java_bytes(JNIEnv* env, const leveldb::Slice& value) {
  if (value.size() > 32 * 1024 * 1024) throw std::runtime_error("Record exceeds 32 MiB");
  auto out = env->NewByteArray(static_cast<jsize>(value.size()));
  if (!out) throw std::runtime_error("Record allocation failed");
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
  return out;
}
template<class Map> auto& lookup(Map& map, jlong id) {
  auto found = map.find(id);
  if (found == map.end()) throw std::runtime_error("Native resource is closed");
  return found->second;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_cn_mcworld_translator_engine_NativeBridge_openDb(JNIEnv* env, jobject, jstring path) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    auto holder = std::make_shared<Database>();
    leveldb::Options options; options.create_if_missing = false; options.paranoid_checks = true;
    options.compression = leveldb::kZlibRawCompression;
    leveldb::DB* db = nullptr;
    status(leveldb::DB::Open(options, text(env, path), &db)); holder->db.reset(db);
    auto id = next_id++; databases[id] = holder; return id;
  } catch (const std::exception& e) { failure(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_cn_mcworld_translator_engine_NativeBridge_closeDb(JNIEnv* env, jobject, jlong id) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    auto& db = lookup(databases, id);
    for (const auto& pair : cursors) if (pair.second->owner == db) throw std::runtime_error("Close database cursors first");
    databases.erase(id);
  } catch (const std::exception& e) { failure(env, e); }
}
extern "C" JNIEXPORT jlong JNICALL Java_cn_mcworld_translator_engine_NativeBridge_cursor(JNIEnv* env, jobject, jlong id) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    auto cursor = std::make_unique<Cursor>(); cursor->owner = lookup(databases, id);
    cursor->snapshot = cursor->owner->db->GetSnapshot();
    leveldb::ReadOptions options; options.verify_checksums = true; options.snapshot = cursor->snapshot;
    cursor->iterator.reset(cursor->owner->db->NewIterator(options)); cursor->iterator->SeekToFirst();
    auto handle = next_id++; cursors[handle] = std::move(cursor); return handle;
  } catch (const std::exception& e) { failure(env, e); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL Java_cn_mcworld_translator_engine_NativeBridge_cursorRange(JNIEnv* env, jobject, jlong id, jint start, jint end) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    if (start < 0 || end > 256 || start >= end) throw std::runtime_error("Invalid database key range");
    auto cursor = std::make_unique<Cursor>(); cursor->owner = lookup(databases, id);
    cursor->snapshot = cursor->owner->db->GetSnapshot();
    leveldb::ReadOptions options; options.verify_checksums = true; options.snapshot = cursor->snapshot;
    cursor->iterator.reset(cursor->owner->db->NewIterator(options));
    char first = static_cast<char>(start);
    if (start == 0) cursor->iterator->SeekToFirst();
    else cursor->iterator->Seek(leveldb::Slice(&first, 1));
    cursor->endByteExclusive = end;
    auto handle = next_id++; cursors[handle] = std::move(cursor); return handle;
  } catch (const std::exception& e) { failure(env, e); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL Java_cn_mcworld_translator_engine_NativeBridge_countRecords(JNIEnv* env, jobject, jlong id) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    auto& db = lookup(databases, id);
    leveldb::ReadOptions options; options.verify_checksums = true;
    std::unique_ptr<leveldb::Iterator> iterator(db->db->NewIterator(options));
    jlong count = 0;
    for (iterator->SeekToFirst(); iterator->Valid(); iterator->Next()) ++count;
    status(iterator->status()); return count;
  } catch (const std::exception& e) { failure(env, e); return -1; }
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_cn_mcworld_translator_engine_NativeBridge_next(JNIEnv* env, jobject, jlong id) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    auto& cursor = lookup(cursors, id);
    if (!cursor->iterator->Valid()) { status(cursor->iterator->status()); return nullptr; }
    if (!cursor->iterator->key().empty() && static_cast<unsigned char>(cursor->iterator->key().data()[0]) >= cursor->endByteExclusive) return nullptr;
    auto out = env->NewObjectArray(2, env->FindClass("[B"), nullptr);
    auto key = java_bytes(env, cursor->iterator->key()); env->SetObjectArrayElement(out, 0, key); env->DeleteLocalRef(key);
    if (cursor->iterator->value().size() <= 32 * 1024 * 1024) {
      auto value = java_bytes(env, cursor->iterator->value()); env->SetObjectArrayElement(out, 1, value); env->DeleteLocalRef(value);
    }
    cursor->iterator->Next(); return out;
  } catch (const std::exception& e) { failure(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_cn_mcworld_translator_engine_NativeBridge_nextTextCandidate(JNIEnv* env, jobject, jlong id) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    auto& cursor = lookup(cursors, id);
    if (!cursor->iterator->Valid() || (!cursor->iterator->key().empty() && static_cast<unsigned char>(cursor->iterator->key().data()[0]) >= cursor->endByteExclusive)) {
      status(cursor->iterator->status()); return nullptr;
    }
    unsigned scanned = 0;
    auto out = env->NewObjectArray(3, env->FindClass("[B"), nullptr);
    while (scanned < 500 && cursor->iterator->Valid() &&
           (cursor->iterator->key().empty() || static_cast<unsigned char>(cursor->iterator->key().data()[0]) < cursor->endByteExclusive)) {
      auto key = cursor->iterator->key();
      ++scanned;
      bool actor = key.starts_with("actorprefix");
      bool player = key == "~local_player" || key.starts_with("player_server_");
      bool chunk = (key.size() == 9 || key.size() == 13) &&
          (static_cast<unsigned char>(key.data()[key.size() - 1]) == 0x31 ||
           static_cast<unsigned char>(key.data()[key.size() - 1]) == 0x32);
      if (actor || player || chunk) {
        auto k = java_bytes(env, key); env->SetObjectArrayElement(out, 0, k); env->DeleteLocalRef(k);
        if (cursor->iterator->value().size() <= 32 * 1024 * 1024) {
          auto v = java_bytes(env, cursor->iterator->value()); env->SetObjectArrayElement(out, 1, v); env->DeleteLocalRef(v);
        }
        cursor->iterator->Next();
        break;
      }
      cursor->iterator->Next();
    }
    status(cursor->iterator->status());
    jbyte count[4] = {static_cast<jbyte>(scanned), static_cast<jbyte>(scanned >> 8),
                      static_cast<jbyte>(scanned >> 16), static_cast<jbyte>(scanned >> 24)};
    auto n = env->NewByteArray(4); env->SetByteArrayRegion(n, 0, 4, count);
    env->SetObjectArrayElement(out, 2, n); env->DeleteLocalRef(n);
    return out;
  } catch (const std::exception& e) { failure(env, e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_cn_mcworld_translator_engine_NativeBridge_closeCursor(JNIEnv*, jobject, jlong id) {
  std::lock_guard<std::mutex> guard(lock); cursors.erase(id);
}
extern "C" JNIEXPORT void JNICALL Java_cn_mcworld_translator_engine_NativeBridge_write(JNIEnv* env, jobject, jlong id, jobjectArray keys, jobjectArray originals, jobjectArray replacements) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    auto& db = lookup(databases, id);
    jsize count = env->GetArrayLength(keys);
    if (env->GetArrayLength(originals) != count || env->GetArrayLength(replacements) != count) throw std::runtime_error("Batch length mismatch");
    leveldb::WriteBatch batch;
    for (jsize i = 0; i < count; ++i) {
      auto k = static_cast<jbyteArray>(env->GetObjectArrayElement(keys, i));
      auto o = static_cast<jbyteArray>(env->GetObjectArrayElement(originals, i));
      auto r = static_cast<jbyteArray>(env->GetObjectArrayElement(replacements, i));
      auto key = bytes(env, k); auto expected = bytes(env, o); auto replacement = bytes(env, r);
      env->DeleteLocalRef(k); env->DeleteLocalRef(o); env->DeleteLocalRef(r);
      std::string current; status(db->db->Get(leveldb::ReadOptions(), key, &current));
      if (current != expected) throw std::runtime_error("Database source record changed");
      batch.Put(key, replacement);
    }
    leveldb::WriteOptions options; options.sync = true; status(db->db->Write(options, &batch));
  } catch (const std::exception& e) { failure(env, e); }
}
extern "C" JNIEXPORT jlong JNICALL Java_cn_mcworld_translator_engine_NativeBridge_openSp(JNIEnv* env, jobject, jstring path) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    auto sp = std::make_unique<sentencepiece::SentencePieceProcessor>();
    auto result = sp->Load(text(env, path)); if (!result.ok()) throw std::runtime_error(result.ToString());
    auto id = next_id++; tokenizers[id] = std::move(sp); return id;
  } catch (const std::exception& e) { failure(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_cn_mcworld_translator_engine_NativeBridge_closeSp(JNIEnv*, jobject, jlong id) {
  std::lock_guard<std::mutex> guard(lock); tokenizers.erase(id);
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_cn_mcworld_translator_engine_NativeBridge_encode(JNIEnv* env, jobject, jlong id, jstring input) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    std::vector<std::string> pieces;
    auto result = lookup(tokenizers, id)->Encode(text(env, input), &pieces); if (!result.ok()) throw std::runtime_error(result.ToString());
    auto out = env->NewObjectArray(static_cast<jsize>(pieces.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < pieces.size(); ++i) { auto s = java_text(env, pieces[i]); env->SetObjectArrayElement(out, i, s); env->DeleteLocalRef(s); }
    return out;
  } catch (const std::exception& e) { failure(env, e); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL Java_cn_mcworld_translator_engine_NativeBridge_decode(JNIEnv* env, jobject, jlong id, jobjectArray input) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    std::vector<std::string> pieces;
    for (jsize i = 0; i < env->GetArrayLength(input); ++i) { auto s = static_cast<jstring>(env->GetObjectArrayElement(input, i)); pieces.push_back(text(env, s)); env->DeleteLocalRef(s); }
    std::string output; auto result = lookup(tokenizers, id)->Decode(pieces, &output); if (!result.ok()) throw std::runtime_error(result.ToString());
    return java_text(env, output);
  } catch (const std::exception& e) { failure(env, e); return nullptr; }
}
