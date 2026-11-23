APP_PLATFORM := android-23
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
NDK_TOOLCHAIN_VERSION := clang
APP_STL := c++_static

LOCAL_LDFLAGS += "-Wl,-z,max-page-size=16384"
LOCAL_LDFLAGS += "-Wl,-z,common-page-size=16384"