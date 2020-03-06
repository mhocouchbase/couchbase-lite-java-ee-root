#include "c4Base.h"
#include "c4Database.h"

#ifdef _MSC_VER
#ifdef SQLCipherUpgrader_EXPORTS
#define API_EXPORT __declspec(dllexport)
#else
#define API_EXPORT __declspec(dllimport)
#endif
#else
#define API_EXPORT 
#endif

#ifdef __cplusplus
extern "C" {
#endif
    API_EXPORT void c4db_upgrade_sqlcipher(C4Slice srcPath, const C4DatabaseConfig* config, C4Error* err);
#ifdef __cplusplus
}
#endif