
#include "meth.h"
#include "openat.h"
#include "read_cert.h"
#include "SHA1.h"

static const char *SIGN = "3A0F57FE06485D0B90D0ACD990E3A30328E3988D";

extern "C" {

int verifySign(JNIEnv *env) {
#ifdef NAGRAM_DEBUG_BUILD
    // Debug: используем стандартную отладочную подпись Android.
    LOGI("Debug build: signature verification skipped");
    return JNI_OK;
#else
    // Release: проверяем подпись APK.
    jobject application = getApplication(env);
    if (application == nullptr) {
        return JNI_ERR;
    }

    const char *resourcePath = getApkPath(env, application);
    env->DeleteLocalRef(application);

    intptr_t fd = openAt(AT_FDCWD, resourcePath, O_RDONLY);
    if (fd < 0) {
        return JNI_ERR;
    }

    std::string sign = read_certificate(fd);

    if (!more_check(fd, resourcePath)) {
        close(fd);
        return JNI_ERR;
    }

    close(fd);

    std::string s = toolkit::SHA1::encode(sign);

    // Переводим SHA1 в верхний регистр.
    for (char &c : s) {
        if (c >= 'a' && c <= 'z') {
            c = c - 'a' + 'A';
        }
    }

    const char *hex_sha = s.c_str();

    LOGI("应用读取的签名为：%s", hex_sha);
    LOGI("应用预置的签名为：%s", SIGN);

    int result = strcmp(hex_sha, SIGN);

    if (result == 0) {
        LOGI("签名一致");
        return JNI_OK;
    }

    LOGE("签名校验失败");
    return JNI_ERR;
#endif
}

}
