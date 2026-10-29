#ifndef ICHUCHU_SSH_COMPAT_H
#define ICHUCHU_SSH_COMPAT_H

#include <libssh2.h>

/* Keep default allocator selection in C: Zig 0.15 translate-c cannot represent
 * the allocator callback declarators in the current upstream header. */
LIBSSH2_SESSION *ichuchu_ssh_session_init(void);

#endif
