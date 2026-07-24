/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.jdbc.hive.auth.oidc;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opens the system default browser, with an OS command-line fallback. */
public final class BrowserLauncher {

  private static final Logger LOG = LoggerFactory.getLogger(BrowserLauncher.class);

  private BrowserLauncher() {}

  /**
   * @return true if this JVM looks capable of launching a browser (has a display / not headless).
   */
  public static boolean isBrowsingSupported() {
    if (java.awt.GraphicsEnvironment.isHeadless()) {
      return false;
    }
    // On Linux a desktop session is signalled by DISPLAY/WAYLAND_DISPLAY; without it, xdg-open
    // fails.
    String os = osName();
    if (os.contains("linux")) {
      return System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
    }
    return true;
  }

  /**
   * Attempt to open {@code uri} in the default browser.
   *
   * @return true if a browser was launched, false if no mechanism was available
   */
  public static boolean open(String uri) {
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI.create(uri));
        return true;
      }
    } catch (Throwable t) {
      LOG.debug("Desktop.browse failed, falling back to OS command: {}", t.getMessage());
    }
    return openWithOsCommand(uri);
  }

  private static boolean openWithOsCommand(String uri) {
    String os = osName();
    String[] cmd;
    if (os.contains("mac")) {
      cmd = new String[] {"open", uri};
    } else if (os.contains("win")) {
      cmd = new String[] {"rundll32", "url.dll,FileProtocolHandler", uri};
    } else {
      cmd = new String[] {"xdg-open", uri};
    }
    try {
      new ProcessBuilder(cmd).inheritIO().start();
      return true;
    } catch (IOException e) {
      LOG.debug("OS browser command {} failed: {}", cmd[0], e.getMessage());
      return false;
    }
  }

  private static String osName() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
  }
}
