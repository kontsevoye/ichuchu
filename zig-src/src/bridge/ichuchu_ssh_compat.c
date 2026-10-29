#include "ichuchu_ssh_compat.h"

LIBSSH2_SESSION *ichuchu_ssh_session_init(void) {
    return libssh2_session_init();
}
