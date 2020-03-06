#include <sqlite3.h>
#include <string>
#include "SQLCipherUpgrader.hh"
#include "FilePath.hh"
using namespace litecore;
using namespace std;

extern "C" API_EXPORT void c4db_upgrade_sqlcipher(C4Slice srcPath, const C4DatabaseConfig* config, C4Error* err)
{
	// Get the userVersion of the db:
	string srcPathStr((char *)srcPath.buf, srcPath.size);
	FilePath dbPath(srcPathStr, "db.sqlite3");
	sqlite3* src = nullptr;
	int rc = sqlite3_open_v2(dbPath.path().c_str(), &src, SQLITE_OPEN_READONLY, nullptr);
	if(rc != SQLITE_OK) {
		return;
	}

	rc = sqlite3_key_v2(src, nullptr, config->encryptionKey.bytes, 32);
	if(rc != SQLITE_OK) {
		sqlite3_close_v2(src);
		return;
	}

	rc = sqlite3_rekey_v2(src, nullptr, nullptr, 0);
	if(rc != SQLITE_OK) {
		sqlite3_close_v2(src);
		return;
	}

	C4DatabaseConfig altConfig = *config;
	altConfig.encryptionKey.algorithm = kC4EncryptionNone;

	C4Database* newDb = c4db_open(srcPath, &altConfig, err);
	c4db_rekey(newDb, &config->encryptionKey, err);
}