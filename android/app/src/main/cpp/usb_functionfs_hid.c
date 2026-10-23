/* Adapted from cmwen/codex-micro-tablet d2a3cf0 (MIT); see THIRD_PARTY_NOTICES.md. */
#include <stdbool.h>
#include <jni.h>
#include <linux/hid.h>
#include <linux/aio_abi.h>
#include <linux/usb/ch9.h>
#include <linux/usb/functionfs.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#define MAX_REPORT_SIZE 64
#define SETUP_LIMIT 64
#define SEND_TIMEOUT_MS 500

struct ffs_descriptors_header {
    uint32_t magic;
    uint32_t length;
    uint32_t fs_count;
    uint32_t hs_count;
} __attribute__((packed));

struct ffs_strings_header {
    uint32_t magic;
    uint32_t length;
    uint32_t str_count;
    uint32_t lang_count;
    uint16_t language;
} __attribute__((packed));

struct usb_interface_descriptor_local {
    uint8_t length, type, number, alternate, endpoints, interface_class;
    uint8_t subclass, protocol, string;
} __attribute__((packed));

struct hid_descriptor_local {
    uint8_t length, type;
    uint16_t version;
    uint8_t country, descriptor_count, report_type;
    uint16_t report_length;
} __attribute__((packed));

struct usb_endpoint_descriptor_local {
    uint8_t length, type, address, attributes;
    uint16_t max_packet;
    uint8_t interval;
} __attribute__((packed));

struct usb_ctrlrequest_local {
    uint8_t request_type, request;
    uint16_t value, index, length;
} __attribute__((packed));

struct hid_session {
    JavaVM *vm;
    jobject owner;
    jmethodID on_report;
    int ep0, ep_in, ep_out, stop_fd, state_fd;
    pthread_t setup_thread, output_thread;
    bool setup_started, output_started;
    atomic_bool stopping;
    atomic_bool enabled;
    aio_context_t in_aio, out_aio;
    struct iocb in_iocb, out_iocb;
    bool in_pending, out_pending;
    uint8_t in_buffer[MAX_REPORT_SIZE], out_buffer[MAX_REPORT_SIZE];
    uint8_t last_report[MAX_REPORT_SIZE];
    uint8_t report_id, idle;
    size_t report_size;
    uint8_t *report_descriptor;
    size_t report_descriptor_length;
};

static pthread_mutex_t session_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t report_lock = PTHREAD_MUTEX_INITIALIZER;
static struct hid_session *current_session;

static void throw_io(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

static void throw_errno(JNIEnv *env, const char *operation) {
    char message[256];
    (void)snprintf(message, sizeof(message), "%s: %s", operation, strerror(errno));
    throw_io(env, message);
}

static uint16_t read_le16(const uint8_t *p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static void write_le16(uint8_t *p, uint16_t value) {
    p[0] = (uint8_t)value;
    p[1] = (uint8_t)(value >> 8);
}

static int write_blob(int fd, const void *buffer, size_t length) {
    ssize_t result;
    do {
        result = write(fd, buffer, length);
    } while (result < 0 && errno == EINTR);
    if (result != (ssize_t)length) {
        if (result >= 0) errno = EIO;
        return -1;
    }
    return 0;
}

static int write_descriptors(struct hid_session *s) {
    uint8_t block[sizeof(struct ffs_descriptors_header) + 2 * (9 + 9 + 7 + 7)];
    const struct usb_interface_descriptor_local interface_desc = {
        9, USB_DT_INTERFACE, 0, 0, 2, USB_CLASS_HID, 0, 0, 1
    };
    const struct hid_descriptor_local hid_desc = {
        9, HID_DT_HID, 0x0111, 0, 1, HID_DT_REPORT,
        (uint16_t)s->report_descriptor_length
    };
    const struct usb_endpoint_descriptor_local fs_in = {
        7, USB_DT_ENDPOINT, 0x81, USB_ENDPOINT_XFER_INT, 64, 1
    };
    const struct usb_endpoint_descriptor_local fs_out = {
        7, USB_DT_ENDPOINT, 0x02, USB_ENDPOINT_XFER_INT, 64, 1
    };
    struct usb_endpoint_descriptor_local hs_in = fs_in;
    struct usb_endpoint_descriptor_local hs_out = fs_out;
    struct ffs_descriptors_header header;
    size_t offset = sizeof(header);

    hs_in.interval = 4;
    hs_out.interval = 4;
    header.magic = FUNCTIONFS_DESCRIPTORS_MAGIC;
    header.length = (uint32_t)sizeof(block);
    header.fs_count = 4;
    header.hs_count = 4;
    memcpy(block, &header, sizeof(header));
#define APPEND_DESC(desc) do { memcpy(block + offset, &(desc), sizeof(desc)); offset += sizeof(desc); } while (0)
    APPEND_DESC(interface_desc);
    APPEND_DESC(hid_desc);
    APPEND_DESC(fs_in);
    APPEND_DESC(fs_out);
    APPEND_DESC(interface_desc);
    APPEND_DESC(hid_desc);
    APPEND_DESC(hs_in);
    APPEND_DESC(hs_out);
#undef APPEND_DESC
    if (offset != sizeof(block)) {
        errno = EINVAL;
        return -1;
    }
    return write_blob(s->ep0, block, sizeof(block));
}

static int write_strings(struct hid_session *s) {
    static const char string_data[] = "Tablet HID Gamepad";
    uint8_t blob[sizeof(struct ffs_strings_header) + sizeof(string_data)];
    struct ffs_strings_header header = {
        FUNCTIONFS_STRINGS_MAGIC,
        (uint32_t)(sizeof(struct ffs_strings_header) + sizeof(string_data)),
        1,
        1,
        0x0409
    };
    memcpy(blob, &header, sizeof(header));
    memcpy(blob + sizeof(header), string_data, sizeof(string_data));
    return write_blob(s->ep0, blob, sizeof(blob));
}

static void report_callback(struct hid_session *s, const uint8_t *report, size_t length) {
    JNIEnv *env = NULL;
    bool attached = false;
    jbyteArray body;
    const uint8_t *payload = report;
    size_t payload_length = length;
    jint status = (*s->vm)->GetEnv(s->vm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*s->vm)->AttachCurrentThread(s->vm, &env, NULL) != JNI_OK) return;
        attached = true;
    } else if (status != JNI_OK) {
        return;
    }

    if (payload_length == s->report_size && payload[0] == s->report_id) {
        payload++;
        payload_length--;
    }
    if (payload_length < 1 || payload_length >= MAX_REPORT_SIZE) {
        if (attached) (*s->vm)->DetachCurrentThread(s->vm);
        return;
    }
    body = (*env)->NewByteArray(env, (jsize)payload_length);
    if (body != NULL) {
        (*env)->SetByteArrayRegion(env, body, 0, (jsize)payload_length, (const jbyte *)payload);
        if (!(*env)->ExceptionCheck(env)) {
            (*env)->CallVoidMethod(env, s->owner, s->on_report, body);
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, body);
    }
    if (attached) (*s->vm)->DetachCurrentThread(s->vm);
}

static void stall_setup(struct hid_session *s, const struct usb_ctrlrequest_local *setup) {
    uint8_t byte;
    if ((setup->request_type & USB_DIR_IN) != 0) {
        (void)read(s->ep0, &byte, 0);
    } else {
        (void)write(s->ep0, &byte, 0);
    }
}

static void setup_in(struct hid_session *s, const void *data, size_t size) {
    ssize_t result;
    do {
        result = write(s->ep0, data, size);
    } while (result < 0 && errno == EINTR);
    if (result != (ssize_t)size && errno != EIDRM && errno != EINTR) {
        /* A canceled control request is expected when the host changes setup. */
    }
}

static void setup_out(struct hid_session *s, uint16_t length) {
    uint8_t report[SETUP_LIMIT];
    if (length == 0) {
        (void)read(s->ep0, report, 0);
        return;
    }
    if (length > sizeof(report)) {
        struct usb_ctrlrequest_local out_setup = { .request_type = 0 };
        stall_setup(s, &out_setup);
        return;
    }
    ssize_t count;
    do {
        count = read(s->ep0, report, length);
    } while (count < 0 && errno == EINTR);
    if (count > 0) report_callback(s, report, (size_t)count);
}

static void handle_setup(struct hid_session *s, const struct usb_ctrlrequest_local *setup) {
    const uint8_t type = setup->request_type;
    const uint8_t request = setup->request;
    const uint8_t descriptor_type = (uint8_t)(setup->value >> 8);
    const uint8_t descriptor_index = (uint8_t)setup->value;
    const uint8_t report_type = (uint8_t)(setup->value >> 8);
    const uint8_t report_id = (uint8_t)setup->value;
    const uint16_t length = setup->length;
    uint8_t report[MAX_REPORT_SIZE] = {0};
    pthread_mutex_lock(&report_lock);
    memcpy(report, s->last_report, s->report_size);
    pthread_mutex_unlock(&report_lock);
    uint8_t protocol = 1;
    uint16_t response_length;

    if (type == (USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_INTERFACE) &&
        request == USB_REQ_GET_DESCRIPTOR && descriptor_index == 0) {
        if (descriptor_type == HID_DT_HID) {
            uint8_t descriptor[9] = { 9, HID_DT_HID, 0x11, 0x01, 0, 1, HID_DT_REPORT, 0, 0 };
            write_le16(descriptor + 7, (uint16_t)s->report_descriptor_length);
            setup_in(s, descriptor, length < sizeof(descriptor) ? length : sizeof(descriptor));
            return;
        }
        if (descriptor_type == HID_DT_REPORT) {
            setup_in(s, s->report_descriptor,
                     length < s->report_descriptor_length ? length : s->report_descriptor_length);
            return;
        }
    }

    if ((type & (USB_TYPE_MASK | USB_RECIP_MASK)) != (USB_TYPE_CLASS | USB_RECIP_INTERFACE)) {
        stall_setup(s, setup);
        return;
    }

    switch (request) {
        case HID_REQ_GET_REPORT:
            if ((type & USB_DIR_IN) == 0 || report_id != s->report_id || report_type != 1) break;
            response_length = length < s->report_size ? length : (uint16_t)s->report_size;
            setup_in(s, report, response_length);
            return;
        case HID_REQ_SET_REPORT:
            /* This descriptor has input only: reject undeclared output/feature reports. */
            break;
        case HID_REQ_GET_IDLE:
            if ((type & USB_DIR_IN) == 0 || length == 0) break;
            setup_in(s, &s->idle, 1);
            return;
        case HID_REQ_SET_IDLE:
            if ((type & USB_DIR_IN) != 0 || length != 0 || (report_id != 0 && report_id != s->report_id)) break;
            s->idle = (uint8_t)(setup->value >> 8);
            setup_out(s, length);
            return;
        case HID_REQ_GET_PROTOCOL:
            if ((type & USB_DIR_IN) == 0 || length == 0) break;
            setup_in(s, &protocol, 1);
            return;
        case HID_REQ_SET_PROTOCOL:
            if ((type & USB_DIR_IN) != 0 || setup->value != 1 || length != 0) break;
            setup_out(s, length);
            return;
        default:
            break;
    }
    stall_setup(s, setup);
}

static void *setup_loop(void *argument) {
    struct hid_session *s = (struct hid_session *)argument;
    struct pollfd fds[2] = { { s->ep0, POLLIN, 0 }, { s->stop_fd, POLLIN, 0 } };
    while (!atomic_load(&s->stopping)) {
        int ready = poll(fds, 2, -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if ((fds[1].revents & POLLIN) != 0) break;
        if ((fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) break;
        if ((fds[0].revents & POLLIN) != 0) {
            struct usb_functionfs_event event;
            ssize_t count = read(s->ep0, &event, sizeof(event));
            if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            if (count != (ssize_t)sizeof(event)) continue;
            if (event.type == FUNCTIONFS_ENABLE) {
                atomic_store(&s->enabled, true);
                uint64_t one = 1;
                (void)write(s->state_fd, &one, sizeof(one));
            } else if (event.type == FUNCTIONFS_DISABLE || event.type == FUNCTIONFS_UNBIND) {
                atomic_store(&s->enabled, false);
                uint64_t one = 1;
                (void)write(s->state_fd, &one, sizeof(one));
            } else if (event.type == FUNCTIONFS_SETUP) {
                struct usb_ctrlrequest_local setup;
                memcpy(&setup, &event.u.setup, sizeof(setup));
                setup.value = read_le16((const uint8_t *)&event.u.setup.wValue);
                setup.index = read_le16((const uint8_t *)&event.u.setup.wIndex);
                setup.length = read_le16((const uint8_t *)&event.u.setup.wLength);
                handle_setup(s, &setup);
            }
        }
    }
    return NULL;
}

static int aio_submit(aio_context_t context, int fd, struct iocb *request,
                      uint16_t opcode, void *buffer, size_t length) {
    struct iocb *requests[1] = { request };
    memset(request, 0, sizeof(*request));
    request->aio_data = (uint64_t)(uintptr_t)request;
    request->aio_lio_opcode = opcode;
    request->aio_fildes = (uint32_t)fd;
    request->aio_buf = (uint64_t)(uintptr_t)buffer;
    request->aio_nbytes = (uint64_t)length;
    request->aio_offset = 0;
    return (int)syscall(__NR_io_submit, context, 1L, requests);
}

static int aio_wait(aio_context_t context, struct io_event *event, int timeout_ms) {
    struct timespec timeout;
    timeout.tv_sec = timeout_ms / 1000;
    timeout.tv_nsec = (long)(timeout_ms % 1000) * 1000000L;
    return (int)syscall(__NR_io_getevents, context, 1L, 1L, event, &timeout);
}

static int aio_cancel(aio_context_t context, struct iocb *request, struct io_event *event) {
    return (int)syscall(__NR_io_cancel, context, request, event);
}

static int aio_cancel_and_reap(aio_context_t context, struct iocb *request,
                               struct io_event *event) {
    int cancelled = aio_cancel(context, request, event);
    if (cancelled == 0) return 1;
    int completed = aio_wait(context, event, 0);
    return completed > 0 ? 1 : 0;
}

static void wait_for_state_event(struct hid_session *s, int timeout_ms) {
    struct pollfd fds[2] = { { s->state_fd, POLLIN, 0 }, { s->stop_fd, POLLIN, 0 } };
    int ready;
    do {
        ready = poll(fds, 2, timeout_ms);
    } while (ready < 0 && errno == EINTR);
    if (ready > 0 && (fds[0].revents & POLLIN) != 0) {
        uint64_t value;
        while (read(s->state_fd, &value, sizeof(value)) < 0 && errno == EINTR) { }
    }
}

static void wait_for_enable_or_stop(struct hid_session *s) {
    while (!atomic_load(&s->stopping) && !atomic_load(&s->enabled)) {
        wait_for_state_event(s, -1);
    }
}

static void *output_loop(void *argument) {
    struct hid_session *s = (struct hid_session *)argument;
    while (!atomic_load(&s->stopping)) {
        struct io_event event;
        if (!atomic_load(&s->enabled)) {
            wait_for_enable_or_stop(s);
            continue;
        }
        if (aio_submit(s->out_aio, s->ep_out, &s->out_iocb, IOCB_CMD_PREAD,
                       s->out_buffer, sizeof(s->out_buffer)) < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == ESHUTDOWN) {
                wait_for_state_event(s, 100);
                continue;
            }
            break;
        }
        s->out_pending = true;
        while (s->out_pending) {
            int completed = aio_wait(s->out_aio, &event, 100);
            if (completed < 0 && errno != EINTR) {
                if (atomic_load(&s->stopping) || !atomic_load(&s->enabled)) {
                    if (aio_cancel_and_reap(s->out_aio, &s->out_iocb, &event)) {
                        s->out_pending = false;
                    }
                } else {
                    return NULL;
                }
                continue;
            }
            if (completed > 0) {
                s->out_pending = false;
                if (event.res > 0) report_callback(s, s->out_buffer, (size_t)event.res);
            } else if (atomic_load(&s->stopping) || !atomic_load(&s->enabled)) {
                if (aio_cancel_and_reap(s->out_aio, &s->out_iocb, &event)) s->out_pending = false;
            }
        }
    }
    return NULL;
}

static void close_session(struct hid_session *s) {
    if (s == NULL) return;
    atomic_store(&s->stopping, true);
    atomic_store(&s->enabled, false);
    if (s->stop_fd >= 0) {
        uint64_t one = 1;
        (void)write(s->stop_fd, &one, sizeof(one));
    }
    if (s->state_fd >= 0) {
        uint64_t one = 1;
        (void)write(s->state_fd, &one, sizeof(one));
    }
    if (s->setup_started) pthread_join(s->setup_thread, NULL);
    if (s->output_started) pthread_join(s->output_thread, NULL);
    if (s->in_pending) {
        struct io_event event;
        (void)aio_cancel_and_reap(s->in_aio, &s->in_iocb, &event);
    }
    if (s->ep_in >= 0) close(s->ep_in);
    if (s->ep_out >= 0) close(s->ep_out);
    if (s->ep0 >= 0) close(s->ep0);
    if (s->stop_fd >= 0) close(s->stop_fd);
    if (s->state_fd >= 0) close(s->state_fd);
    if (s->in_aio != 0) (void)syscall(__NR_io_destroy, s->in_aio);
    if (s->out_aio != 0) (void)syscall(__NR_io_destroy, s->out_aio);
    if (s->owner != NULL) {
        JNIEnv *env = NULL;
        bool attached = false;
        jint status = (*s->vm)->GetEnv(s->vm, (void **)&env, JNI_VERSION_1_6);
        if (status == JNI_EDETACHED && (*s->vm)->AttachCurrentThread(s->vm, &env, NULL) == JNI_OK) attached = true;
        if (env != NULL) (*env)->DeleteGlobalRef(env, s->owner);
        if (attached) (*s->vm)->DetachCurrentThread(s->vm);
    }
    free(s->report_descriptor);
    free(s);
}

JNIEXPORT void JNICALL
Java_com_cmwen_tablet_1hid_1gamepad_UsbFunctionFs_start(JNIEnv *env, jobject owner,
                                                        jstring path, jbyteArray descriptor, jint report_id_arg, jint report_size_arg) {
    struct hid_session *s = NULL;
    const char *directory = NULL;
    jbyte *descriptor_bytes = NULL;
    jsize descriptor_length;
    char endpoint[1024];
    jclass owner_class;
    int error_number;

    if (path == NULL || descriptor == NULL || report_id_arg < 1 || report_id_arg > 255 || report_size_arg < 2 || report_size_arg > MAX_REPORT_SIZE) {
        throw_io(env, "FunctionFS path and HID report descriptor are required");
        return;
    }
    descriptor_length = (*env)->GetArrayLength(env, descriptor);
    if (descriptor_length <= 0 || descriptor_length > UINT16_MAX) {
        throw_io(env, "Invalid HID report descriptor length");
        return;
    }

    pthread_mutex_lock(&session_lock);
    if (current_session != NULL) {
        pthread_mutex_unlock(&session_lock);
        throw_io(env, "FunctionFS HID session is already active");
        return;
    }
    s = (struct hid_session *)calloc(1, sizeof(*s));
    if (s == NULL) {
        pthread_mutex_unlock(&session_lock);
        throw_io(env, "Cannot allocate FunctionFS HID session");
        return;
    }
    s->report_id = (uint8_t)report_id_arg;
    s->report_size = (size_t)report_size_arg;
    s->last_report[0] = s->report_id;
    /* Report ID + buttons + null hat + four unsigned centered stick axes.
     * GET_REPORT may arrive before the first interrupt input packet. */
    s->last_report[3] = 8;
    s->last_report[5] = s->last_report[7] = 0x80;
    s->last_report[9] = s->last_report[11] = 0x80;
    s->ep0 = s->ep_in = s->ep_out = s->stop_fd = -1;
    s->state_fd = -1;
    atomic_init(&s->stopping, false);
    atomic_init(&s->enabled, false);
    if ((*env)->GetJavaVM(env, &s->vm) != JNI_OK) {
        throw_io(env, "Cannot access Java VM");
        goto failed;
    }
    s->owner = (*env)->NewGlobalRef(env, owner);
    owner_class = (*env)->GetObjectClass(env, owner);
    s->on_report = owner_class == NULL ? NULL : (*env)->GetMethodID(env, owner_class, "onReport", "([B)V");
    if (owner_class != NULL) (*env)->DeleteLocalRef(env, owner_class);
    if (s->owner == NULL || s->on_report == NULL) {
        if (!(*env)->ExceptionCheck(env)) throw_io(env, "UsbFunctionFs.onReport(ByteArray) is required");
        goto failed;
    }
    s->report_descriptor = (uint8_t *)malloc((size_t)descriptor_length);
    if (s->report_descriptor == NULL) {
        throw_io(env, "Cannot allocate HID report descriptor");
        goto failed;
    }
    s->report_descriptor_length = (size_t)descriptor_length;
    descriptor_bytes = (*env)->GetByteArrayElements(env, descriptor, NULL);
    if (descriptor_bytes == NULL) goto failed;
    memcpy(s->report_descriptor, descriptor_bytes, (size_t)descriptor_length);
    (*env)->ReleaseByteArrayElements(env, descriptor, descriptor_bytes, JNI_ABORT);
    descriptor_bytes = NULL;

    directory = (*env)->GetStringUTFChars(env, path, NULL);
    if (directory == NULL) goto failed;
    if (snprintf(endpoint, sizeof(endpoint), "%s/ep0", directory) >= (int)sizeof(endpoint)) {
        (*env)->ReleaseStringUTFChars(env, path, directory);
        directory = NULL;
        throw_io(env, "FunctionFS path is too long");
        goto failed;
    }
    s->ep0 = open(endpoint, O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (s->ep0 < 0) {
        error_number = errno;
        (*env)->ReleaseStringUTFChars(env, path, directory);
        directory = NULL;
        errno = error_number;
        throw_errno(env, "Open FunctionFS ep0");
        goto failed;
    }
    if (write_descriptors(s) < 0 || write_strings(s) < 0) {
        error_number = errno;
        (*env)->ReleaseStringUTFChars(env, path, directory);
        directory = NULL;
        errno = error_number;
        throw_errno(env, "Initialize FunctionFS HID descriptors");
        goto failed;
    }
    if (snprintf(endpoint, sizeof(endpoint), "%s/ep1", directory) >= (int)sizeof(endpoint)) {
        (*env)->ReleaseStringUTFChars(env, path, directory);
        directory = NULL;
        throw_io(env, "FunctionFS path is too long");
        goto failed;
    }
    s->ep_in = open(endpoint, O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (snprintf(endpoint, sizeof(endpoint), "%s/ep2", directory) >= (int)sizeof(endpoint)) {
        (*env)->ReleaseStringUTFChars(env, path, directory);
        directory = NULL;
        throw_io(env, "FunctionFS path is too long");
        goto failed;
    }
    s->ep_out = open(endpoint, O_RDWR | O_CLOEXEC | O_NONBLOCK);
    (*env)->ReleaseStringUTFChars(env, path, directory);
    directory = NULL;
    if (s->ep_in < 0 || s->ep_out < 0) {
        throw_errno(env, "Open FunctionFS HID endpoints");
        goto failed;
    }
    s->stop_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (s->stop_fd < 0) {
        throw_errno(env, "Create FunctionFS stop event");
        goto failed;
    }
    s->state_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (s->state_fd < 0) {
        throw_errno(env, "Create FunctionFS state event");
        goto failed;
    }
    if (syscall(__NR_io_setup, 4U, &s->in_aio) < 0) {
        throw_errno(env, "Create FunctionFS IN AIO context");
        goto failed;
    }
    if (syscall(__NR_io_setup, 4U, &s->out_aio) < 0) {
        throw_errno(env, "Create FunctionFS OUT AIO context");
        goto failed;
    }
    if (pthread_create(&s->setup_thread, NULL, setup_loop, s) != 0) {
        throw_io(env, "Cannot start FunctionFS setup thread");
        goto failed;
    }
    s->setup_started = true;
    if (pthread_create(&s->output_thread, NULL, output_loop, s) != 0) {
        throw_io(env, "Cannot start FunctionFS output thread");
        goto failed;
    }
    s->output_started = true;
    current_session = s;
    pthread_mutex_unlock(&session_lock);
    return;

failed:
    if (descriptor_bytes != NULL) (*env)->ReleaseByteArrayElements(env, descriptor, descriptor_bytes, JNI_ABORT);
    if (directory != NULL) (*env)->ReleaseStringUTFChars(env, path, directory);
    pthread_mutex_unlock(&session_lock);
    close_session(s);
}

JNIEXPORT jboolean JNICALL
Java_com_cmwen_tablet_1hid_1gamepad_UsbFunctionFs_send(JNIEnv *env, jobject owner, jbyteArray report) {
    struct hid_session *s;
    jsize length;
    jbyte *bytes;
    int result = -1;
    (void)owner;
    if (report == NULL) return JNI_FALSE;
    length = (*env)->GetArrayLength(env, report);
    if (length < 2 || length > MAX_REPORT_SIZE) return JNI_FALSE;
    bytes = (*env)->GetByteArrayElements(env, report, NULL);
    if (bytes == NULL) return JNI_FALSE;
    pthread_mutex_lock(&session_lock);
    s = current_session;
    if (s != NULL && (size_t)length == s->report_size && (uint8_t)bytes[0] == s->report_id && !atomic_load(&s->stopping) && atomic_load(&s->enabled)) {
        struct io_event event;
        if (s->in_pending) {
            int completed = aio_wait(s->in_aio, &event, 0);
            if (completed > 0) s->in_pending = false;
        }
        if (!s->in_pending) {
            memcpy(s->in_buffer, bytes, s->report_size);
            pthread_mutex_lock(&report_lock);
            memcpy(s->last_report, bytes, s->report_size);
            pthread_mutex_unlock(&report_lock);
            (*env)->ReleaseByteArrayElements(env, report, bytes, JNI_ABORT);
            bytes = NULL;
            if (aio_submit(s->in_aio, s->ep_in, &s->in_iocb, IOCB_CMD_PWRITE,
                           s->in_buffer, s->report_size) == 1) {
                s->in_pending = true;
                int waited = 0;
                while (waited < SEND_TIMEOUT_MS) {
                    int completed = aio_wait(s->in_aio, &event,
                                             SEND_TIMEOUT_MS - waited > 100 ? 100 : SEND_TIMEOUT_MS - waited);
                    if (completed > 0) {
                        s->in_pending = false;
                        result = event.res == (int64_t)s->report_size ? 0 : -1;
                        break;
                    }
                    if (completed < 0 && errno != EINTR) break;
                    waited += 100;
                }
                if (s->in_pending && aio_cancel_and_reap(s->in_aio, &s->in_iocb, &event)) {
                    s->in_pending = false;
                }
            }
        }
    }
    pthread_mutex_unlock(&session_lock);
    if (bytes != NULL) (*env)->ReleaseByteArrayElements(env, report, bytes, JNI_ABORT);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_cmwen_tablet_1hid_1gamepad_UsbFunctionFs_stop(JNIEnv *env, jobject owner) {
    struct hid_session *s;
    (void)env;
    (void)owner;
    pthread_mutex_lock(&session_lock);
    s = current_session;
    current_session = NULL;
    pthread_mutex_unlock(&session_lock);
    close_session(s);
}
