package dev.volytragoto.brain;

import dev.volytragoto.modules.VolytraGoto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The flight log. The detailed lines ("[VG] ..." in latest.log: once a second where we are and what we're doing, plus
 * every event) are only written while the module's debug-log setting is on; warnings and errors always are.
 */
final class DebugLog {
    private static final Logger LOG = LoggerFactory.getLogger("VolytraGoto");

    void info(String msg, Object... args) {
        VolytraGoto m = VolytraGoto.INSTANCE;
        if (m != null && m.debugLog.get()) LOG.info(msg, args);
    }

    void warn(String msg, Object... args) {
        LOG.warn(msg, args);
    }

    void error(String msg, Object... args) {
        LOG.error(msg, args);
    }
}
