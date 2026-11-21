package top.aico.wxcacheviewer.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.UinHash
import top.aico.wxcacheviewer.domain.model.Account
import java.io.File

/**
 * 为账号目录推断可读名称，只作为「手动备注为空时的默认值」。
 *
 * 两个来源：
 * 1. MicroMsg/<hash>/FinderAccounts_01.db 是明文 SQLite，表 FinderAccount 只有一行，
 *    就是该账号自己的视频号资料。因为库跟着账号目录走，所以每个账号都能单独识别。
 * 2. shared_prefs/com.tencent.mm_preferences.xml 只记录「最后登录账号」的昵称，
 *    必须用 md5("mm" + uin) == 目录名 校验通过才采用，避免张冠李戴。
 *
 * 只使用昵称与 uin 两个字段，其余账号信息不解析、不落盘。
 */
class AccountNameRepository(
    context: Context,
    private val executor: RootCommandExecutor
) {
    private val tempRoot = File(context.cacheDir, TEMP_DIR_NAME)

    suspend fun resolveNames(accounts: List<Account>): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (accounts.isEmpty()) return@withContext emptyMap()
            val login = readLoginInfo()
            val loginHash = login.uin?.let(UinHash::of)
            val names = LinkedHashMap<String, String>(accounts.size)
            var fromDb = 0
            var fromLogin = 0
            accounts.forEach { account ->
                val dbName = finderNickname(account.hash)
                val fallback = login.nickname?.takeIf { account.hash == loginHash }
                val name = dbName ?: fallback
                when {
                    dbName != null -> fromDb++
                    fallback != null -> fromLogin++
                }
                if (name != null) names[account.hash] = name
            }
            AppLog.log(
                "account",
                "names=${names.size}/${accounts.size} fromDb=$fromDb fromLogin=$fromLogin " +
                    "loginVerified=${loginHash != null}"
            )
            names
        }

    /** 复制账号自己的视频号资料库并读昵称；任何一步失败都返回 null 交给上层兜底。 */
    private suspend fun finderNickname(hash: String): String? {
        val directory = File(tempRoot, hash)
        val dbFile = File(directory, WxPaths.FINDER_ACCOUNT_DB)
        return try {
            // 目录必须由应用自己创建：root 建出来的目录属主是 root，SQLite 无法在其中建 -shm。
            directory.deleteRecursively()
            if (!directory.mkdirs()) {
                AppLog.log("account", "temp dir unavailable: ${directory.absolutePath}")
                return null
            }
            val result = executor.run(
                WxPaths.copyFinderAccountDbCommand(hash, directory.absolutePath),
                DB_TIMEOUT_MS
            )
            if (result.exitCode != 0 || !result.stdout.contains(OK_MARK)) {
                AppLog.log("account", "finder db unavailable: $hash exit=${result.exitCode}")
                return null
            }
            if (!dbFile.isFile) return null
            queryNickname(dbFile)
        } catch (e: Exception) {
            AppLog.log("account", "finder db failed: $hash ${e.message}")
            null
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun queryNickname(dbFile: File): String? {
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery(NICKNAME_QUERY, null).use { cursor ->
                if (!cursor.moveToFirst()) return null
                return cursor.getString(0)?.let(::cleanName)
            }
        }
    }

    private suspend fun readLoginInfo(): LoginInfo {
        val result = try {
            executor.run(WxPaths.readLoginPrefsCommand(), PREFS_TIMEOUT_MS)
        } catch (e: Exception) {
            AppLog.log("account", "login prefs failed: ${e.message}")
            return LoginInfo(null, null)
        }
        if (result.exitCode != 0) return LoginInfo(null, null)
        val uin = UIN_REGEX.find(result.stdout)?.groupValues?.get(1)
        val nickname = NICKNAME_REGEX.find(result.stdout)?.groupValues?.get(1)?.let(::cleanName)
        return LoginInfo(uin, nickname)
    }

    /** 解码 prefs 里的 XML 转义，并压掉换行、限制长度，避免把脏值带进标题栏。 */
    private fun cleanName(raw: String): String? {
        val decoded = raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
        return decoded.take(MAX_NAME_LENGTH).takeIf { it.isNotEmpty() }
    }

    private data class LoginInfo(val uin: String?, val nickname: String?)

    private companion object {
        const val TEMP_DIR_NAME = "account_name_tmp"
        const val OK_MARK = "OK"
        const val DB_TIMEOUT_MS = 20_000L
        const val PREFS_TIMEOUT_MS = 8_000L
        const val MAX_NAME_LENGTH = 32
        const val NICKNAME_QUERY = "SELECT nickname FROM FinderAccount LIMIT 1"
        val NICKNAME_REGEX = Regex("name=\"last_login_nick_name\">([^<\\n]*)")
        val UIN_REGEX = Regex("name=\"last_login_uin\">(\\d{1,20})")
    }
}
