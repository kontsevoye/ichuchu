#include "ichuchu_ssh_compat.h"

LIBSSH2_SESSION *ichuchu_ssh_session_init(void) {
    return libssh2_session_init();
}

#include <errno.h>

/* libssh2 channel calls return library errors; socket callbacks use errno codes.
 * The parent is always nonblocking and owned by the same executor as the child. */
static ssize_t forwarded_send(libssh2_socket_t fd, const void *buffer,
                              size_t length, int flags, void **abstract) {
    (void)fd;
    (void)flags;
    ssize_t rc = libssh2_channel_write_ex(*abstract, 0, buffer, length);
    if (rc == LIBSSH2_ERROR_EAGAIN) return -EAGAIN;
    return rc < 0 ? -ECONNRESET : rc;
}

static ssize_t forwarded_recv(libssh2_socket_t fd, void *buffer,
                              size_t length, int flags, void **abstract) {
    (void)fd;
    (void)flags;
    LIBSSH2_CHANNEL *channel = *abstract;
    ssize_t rc = libssh2_channel_read_ex(channel, 0, buffer, length);
    if (rc == LIBSSH2_ERROR_EAGAIN) return -EAGAIN;
    if (rc < 0) return -ECONNRESET;
    return rc == 0 && !libssh2_channel_eof(channel) ? -EAGAIN : rc;
}

LIBSSH2_SESSION *ichuchu_ssh_session_init_forwarded(LIBSSH2_CHANNEL *channel) {
    LIBSSH2_SESSION *session = libssh2_session_init_ex(NULL, NULL, NULL, channel);
    if (session) {
        libssh2_session_callback_set2(session, LIBSSH2_CALLBACK_SEND,
                                     (libssh2_cb_generic *)forwarded_send);
        libssh2_session_callback_set2(session, LIBSSH2_CALLBACK_RECV,
                                     (libssh2_cb_generic *)forwarded_recv);
    }
    return session;
}

static ssize_t aborted_send(libssh2_socket_t fd, const void *buffer,
                            size_t length, int flags, void **abstract) {
    (void)fd; (void)buffer; (void)length; (void)flags; (void)abstract;
    return -ECONNRESET;
}

static ssize_t aborted_recv(libssh2_socket_t fd, void *buffer,
                            size_t length, int flags, void **abstract) {
    (void)fd; (void)buffer; (void)length; (void)flags; (void)abstract;
    return -ECONNRESET;
}

void ichuchu_ssh_session_abort_io(LIBSSH2_SESSION *session) {
    libssh2_session_set_blocking(session, 0);
    libssh2_session_callback_set2(session, LIBSSH2_CALLBACK_SEND,
                                 (libssh2_cb_generic *)aborted_send);
    libssh2_session_callback_set2(session, LIBSSH2_CALLBACK_RECV,
                                 (libssh2_cb_generic *)aborted_recv);
}
