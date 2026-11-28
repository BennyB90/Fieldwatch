// SPDX-License-Identifier: MIT
// The calling worker exclusively owns the authorized Java connection until run returns.
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>

struct receiver {
    JNIEnv *env;
    jobject sink;
    jmethodID on_frame;
    jmethodID on_loss;
    jmethodID stopped;
    unsigned char *frame;
    int target;
    int used;
    int fid;
    int corrupt;
    int ended;
    jlong dropped;
    jlong damaged;
};

struct slot {
    struct usbdevfs_urb *urb;
    int pending;
};

static void finish_frame(struct receiver *r) {
    JNIEnv *env = r->env;
    if (r->used == r->target && !r->corrupt) {
        jbyteArray frame = (*env)->NewByteArray(env, r->target);
        if (frame) {
            (*env)->SetByteArrayRegion(env, frame, 0, r->target, (const jbyte *)r->frame);
            if (!(*env)->ExceptionCheck(env))
                (*env)->CallVoidMethod(env, r->sink, r->on_frame, frame);
            (*env)->DeleteLocalRef(env, frame);
        }
    } else {
        ++r->dropped;
        if (r->corrupt) ++r->damaged;
    }
    r->used = 0;
    r->corrupt = 0;
}

static void payload(struct receiver *r, const unsigned char *packet, int length) {
    if (!length) return;
    int header = packet[0];
    if (header < 2 || header > length) {
        r->corrupt = 1;
        return;
    }
    int flags = packet[1];
    int fid = flags & 1;
    if (r->fid != fid) {
        if (r->fid >= 0 && !r->ended) finish_frame(r);
        if (r->fid >= 0) r->corrupt = 0;
        r->fid = fid;
        r->ended = 0;
    }
    if (r->ended) return;
    int bytes = length - header;
    int minimum_header = 2 + ((flags & 4) ? 4 : 0) + ((flags & 8) ? 6 : 0);
    if ((flags & 0x40) || header < minimum_header || bytes > r->target - r->used) r->corrupt = 1;
    if (!r->corrupt) {
        if (bytes > 0) memcpy(r->frame + r->used, packet + header, (size_t)bytes);
        r->used += bytes;
    }
    if (flags & 2) {
        finish_frame(r);
        r->ended = 1;
    }
}

static int reap(int fd, struct usbdevfs_urb **urb) {
    int result;
    do {
        result = ioctl(fd, USBDEVFS_REAPURBNDELAY, urb);
    } while (result < 0 && errno == EINTR);
    return result;
}

static int submit(int fd, struct slot *slot, int packets, int packet_bytes, int address) {
    struct usbdevfs_urb *urb = slot->urb;
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = (unsigned char)address;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->number_of_packets = packets;
    urb->buffer_length = packets * packet_bytes;
    urb->usercontext = slot;
    for (int i = 0; i < packets; ++i) {
        urb->iso_frame_desc[i].length = (unsigned int)packet_bytes;
        urb->iso_frame_desc[i].actual_length = 0;
        urb->iso_frame_desc[i].status = 0;
    }
    if (ioctl(fd, USBDEVFS_SUBMITURB, urb) < 0) return -errno;
    slot->pending = 1;
    return 0;
}

static int receive(int fd, struct receiver *r, int packet_bytes, int packets, int address) {
    struct pollfd poll_fd = {.fd = fd, .events = POLLOUT};
    JNIEnv *env = r->env;
    while (!(*env)->ExceptionCheck(env)) {
        if ((*env)->CallBooleanMethod(env, r->sink, r->stopped)) return 0;
        if ((*env)->ExceptionCheck(env)) return -EINTR;
        int ready = poll(&poll_fd, 1, 40);
        if (ready < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        if (!ready) continue;
        if (poll_fd.revents & (POLLNVAL | POLLHUP | POLLERR)) return -ENODEV;
        struct usbdevfs_urb *urb = NULL;
        if (reap(fd, &urb) < 0) {
            if (errno == EAGAIN) continue;
            return -errno;
        }
        if (!urb || !urb->usercontext) return -EIO;
        struct slot *slot = urb->usercontext;
        slot->pending = 0;
        if (urb->status < 0) return urb->status;
        jlong dropped = r->dropped;
        for (int i = 0; i < packets; ++i) {
            struct usbdevfs_iso_packet_desc *desc = &urb->iso_frame_desc[i];
            if (desc->status || desc->actual_length > (unsigned int)packet_bytes)
                r->corrupt = 1;
            else
                payload(r, (unsigned char *)urb->buffer + i * packet_bytes, (int)desc->actual_length);
            if ((*env)->ExceptionCheck(env)) return -EINTR;
        }
        if (dropped != r->dropped) {
            (*env)->CallVoidMethod(env, r->sink, r->on_loss, r->dropped, r->damaged);
            if ((*env)->ExceptionCheck(env)) return -EINTR;
        }
        int result = submit(fd, slot, packets, packet_bytes, address);
        if (result < 0) return result;
    }
    return -EINTR;
}

JNIEXPORT jint JNICALL
Java_app_fieldwatch_sweep_thermal_UvcNative_run(JNIEnv *env, jobject self, jint fd,
        jint interface_number, jint alt, jint endpoint, jint wmax, jint packets,
        jint count, jint target, jobject sink) {
    (void)self;
    int transactions = (wmax >> 11) & 3;
    int packet_bytes = (wmax & 0x7ff) * (1 + transactions);
    if (fd < 0 || interface_number < 0 || interface_number > 255 || alt < 0 || alt > 255 ||
        (endpoint & 0xf0) != 0x80 || !(endpoint & 0xf) || transactions == 3 ||
        !packet_bytes || (wmax & 0x7ff) > 1024 || packets < 1 || packets > 64 ||
        count < 1 || count > 16 || target < 1 || target > 1024 * 1024 || !sink) return -EINVAL;

    jclass cls = (*env)->GetObjectClass(env, sink);
    if (!cls) return -EINVAL;
    struct receiver r = {.env = env, .sink = sink, .target = target, .fid = -1};
    r.on_frame = (*env)->GetMethodID(env, cls, "onFrame", "([B)V");
    if (!r.on_frame) return -EINVAL;
    r.on_loss = (*env)->GetMethodID(env, cls, "onLoss", "(JJ)V");
    if (!r.on_loss) return -EINVAL;
    r.stopped = (*env)->GetMethodID(env, cls, "stopped", "()Z");
    if (!r.stopped) return -EINVAL;
    struct usbdevfs_setinterface select = {.interface = (unsigned int)interface_number,
                                          .altsetting = (unsigned int)alt};
    if (ioctl(fd, USBDEVFS_SETINTERFACE, &select) < 0) return -errno;
    struct slot slots[16] = {0};
    int result = -ENOMEM;
    r.frame = malloc((size_t)target);
    if (!r.frame) goto cleanup;
    for (int i = 0; i < count; ++i) {
        struct usbdevfs_urb *urb = calloc(1, sizeof(*urb) + (size_t)packets * sizeof(urb->iso_frame_desc[0]));
        if (!urb) goto cleanup;
        slots[i].urb = urb;
        urb->buffer = malloc((size_t)(packet_bytes * packets));
        if (!urb->buffer) goto cleanup;
        int rc = submit(fd, &slots[i], packets, packet_bytes, endpoint);
        if (rc < 0) {
            result = rc;
            goto cleanup;
        }
    }
    result = receive(fd, &r, packet_bytes, packets, endpoint);
cleanup:
    // DISCARDURB synchronously unlinks an active URB; EINVAL includes already completed URBs.
    for (int i = 0; i < count; ++i) {
        if (!slots[i].pending) continue;
        int rc;
        do { rc = ioctl(fd, USBDEVFS_DISCARDURB, slots[i].urb); } while (rc < 0 && errno == EINTR);
    }
    struct usbdevfs_urb *completed;
    while (reap(fd, &completed) == 0) {}
    select.altsetting = 0;
    ioctl(fd, USBDEVFS_SETINTERFACE, &select);
    for (int i = 0; i < count; ++i) {
        if (slots[i].urb) free(slots[i].urb->buffer);
        free(slots[i].urb);
    }
    free(r.frame);
    return result;
}
