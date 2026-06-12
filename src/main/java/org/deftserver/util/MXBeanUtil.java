package org.deftserver.util;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MXBeanUtil {
	
	private static final Logger logger = LoggerFactory.getLogger(MXBeanUtil.class);
	
	private MXBeanUtil() {}

	/** Registers {@code self} as a JMX MXBean named {@code org.deftserver:type=<type>,name=<class>}
	 *  (idempotent; logs and swallows failures). */
	public static void registerMXBean(Object self, String type) {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		try {
			String mbeanName = "org.deftserver:type=" + type + ",name=" + self.getClass().getSimpleName() + "-" + System.identityHashCode(self);
			ObjectName objectName = new ObjectName(mbeanName);
			if (!mbs.isRegistered(objectName)) {
				mbs.registerMBean(self, objectName);
			}
		}
		catch (Exception e) {
			logger.error("Unable to register {} MXBean", self.getClass().getCanonicalName(), e);
		}
	}

	/** Unregisters the JMX MXBean previously registered for {@code self}/{@code type} (idempotent). */
	public static void unregisterMXBean(Object self, String type) {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		try {
			String mbeanName = "org.deftserver:type=" + type + ",name=" + self.getClass().getSimpleName() + "-" + System.identityHashCode(self);
			ObjectName objectName = new ObjectName(mbeanName);
			if (mbs.isRegistered(objectName)) {
				mbs.unregisterMBean(objectName);
			}
		}
		catch (Exception e) {
			logger.error("Unable to unregister {} MXBean", self.getClass().getCanonicalName(), e);
		}
	}

}
