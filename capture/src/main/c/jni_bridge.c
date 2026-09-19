#include "capture_vpn.h"
#include <android/log.h>
#include <pthread.h>
#include <string.h>

#define TAG "jni_bridge"

static pthread_t g_capture_thread;
static capture_ctx_t *g_ctx = NULL;
static volatile int g_running = 0;
static volatile int g_thread_finished = 0;

/*
 * DNS servers arrive before the capture context exists, so they are parked
 * here and applied when the thread starts. One slot per family: a single slot
 * meant the IPv4 server was overwritten by the IPv6 one whenever the network
 * had both, and the loop then reported "No DNS server configured".
 */
typedef struct {
    char ip[64];
    uint16_t port;
    uint8_t ipver;
    int set;
} pending_dns_t;

static pending_dns_t g_pending_dns[2];

static int dns_slot_for(uint8_t ipver) { return ipver == 6 ? 1 : 0; }

static void apply_pending_dns(capture_ctx_t *ctx) {
    for (int i = 0; i < 2; i++) {
        pending_dns_t *p = &g_pending_dns[i];
        if (!p->set || !p->ip[0]) continue;
        capture_set_dns_server(ctx, p->ip, p->port, p->ipver);
        p->set = 0;
    }
}

static void *capture_thread_func(void *arg) {
    capture_ctx_t *ctx = (capture_ctx_t *)arg;

    (*ctx->java_vm)->AttachCurrentThread(ctx->java_vm, &ctx->jni_env, NULL);
    if (!ctx->jni_env) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "AttachCurrentThread failed");
        g_thread_finished = 1;
        return NULL;
    }

    if (cache_jni_methods(ctx) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to cache JNI methods");
        (*ctx->java_vm)->DetachCurrentThread(ctx->java_vm);
        ctx->jni_env = NULL;
        g_thread_finished = 1;
        return NULL;
    }

    apply_pending_dns(ctx);

    __android_log_print(ANDROID_LOG_INFO, TAG, "Capture thread started, tunfd=%d", ctx->tunfd);
    run_vpn_loop(ctx);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Capture thread exited");

    (*ctx->java_vm)->DetachCurrentThread(ctx->java_vm);
    ctx->jni_env = NULL;
    g_thread_finished = 1;

    return NULL;
}

JNIEXPORT void JNICALL
Java_com_esc_irminsul_capture_internal_CaptureService_nativeRunPacketLoop(
        JNIEnv *env, jobject thiz, jint tunfd) {
    JavaVM *java_vm = NULL;
    (*env)->GetJavaVM(env, &java_vm);

    if (g_ctx && !g_thread_finished) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Previous capture still running, stopping it");
        g_ctx->running = 0;
        pthread_join(g_capture_thread, NULL);
        capture_ctx_destroy(g_ctx);
        g_ctx = NULL;
    }

    g_ctx = capture_ctx_create(java_vm, thiz, 0);
    if (!g_ctx) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create capture context");
        return;
    }

    g_ctx->tunfd = tunfd;
    g_ctx->running = 1;
    g_running = 1;
    g_thread_finished = 0;

    pthread_create(&g_capture_thread, NULL, capture_thread_func, g_ctx);
}

JNIEXPORT void JNICALL
Java_com_esc_irminsul_capture_internal_CaptureService_nativeStopCapture(
        JNIEnv *env, jobject thiz) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Stopping capture");

    if (g_ctx) {
        g_ctx->running = 0;
        g_running = 0;
    }
}

JNIEXPORT void JNICALL
Java_com_esc_irminsul_capture_internal_CaptureService_nativeSetDnsServer(
        JNIEnv *env, jobject thiz, jstring dns_ip, jint dns_port, jint ipver) {
    if (!dns_ip) return;

    const char *dns_str = (*env)->GetStringUTFChars(env, dns_ip, NULL);
    if (!dns_str) return;

    pending_dns_t *slot = &g_pending_dns[dns_slot_for((uint8_t)ipver)];
    strncpy(slot->ip, dns_str, sizeof(slot->ip) - 1);
    slot->ip[sizeof(slot->ip) - 1] = '\0';
    slot->port = (uint16_t)dns_port;
    slot->ipver = (uint8_t)ipver;
    slot->set = 1;

    if (g_ctx && g_ctx->jni_env) {
        capture_set_dns_server(g_ctx, dns_str, (uint16_t)dns_port, (uint8_t)ipver);
        slot->set = 0;
    }

    (*env)->ReleaseStringUTFChars(env, dns_ip, dns_str);
}
