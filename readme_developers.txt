The build process for this project is automated by Maven.
  https://maven.apache.org/

To build, run "./mvnw clean package" in this folder ("mvnw.cmd" on Windows).

The wrapper downloads and pins Maven 3.9.16 on first use, so no system Maven is
needed and CI, contributors and you all build with the same version. A system
"mvn clean package" still works if you prefer.

Requires JDK 17 or newer.


"img/"
  Screenshots.

"skel_common/"
  Files to include in distribution archives.

"skel_win/" and "skel_unix/"
  System-specific files to include in distribution archives.

"skel_exe/"
  Materials to create modman.exe (not part of Maven).
    - Get Launch4j 3.50 or newer: https://launch4j.sourceforge.net/
    - Drag "launch4j_*.xml" onto "launch4jc.exe".
    - "modman.exe" will appear alongside the xml.
    - Drag modman.exe and modman_admin.exe into "skel_win/".
    - Run "mvn clean package".

    - The manifest files will be embedded to prevent VirtualStore redirection.
        http://www.codeproject.com/Articles/17968/Making-Your-Application-UAC-Aware

    - Launch4j 3.50 or newer is REQUIRED. The configs use its schema: it
      removed <jdkPreference> and <runtimeBits>, and made <jre><path>
      required. An older Launch4j will not load these files.

    - The exes currently in skel_win/ predate all of this. They search only
      the legacy SOFTWARE\JavaSoft registry keys, which Adoptium, Microsoft
      and Zulu builds do not create, so they may fail to find a modern JDK
      no matter what is installed. Regenerate them before shipping a
      release, and test on a Windows machine that has only a current JDK.

"auto_update.json"
  Info about the latest release, downloaded periodically by clients.



This project depends on the following libraries.
- Apache HttpComponents
    https://hc.apache.org/
    (For JavaDocs, click HttpCore or HttpClient, then again under "Project reports".)
- Jackson JSON Processor 2.x
    http://jackson.codehaus.org/Home
    (For JavaDocs, look right.)
- PNGJ
    https://code.google.com/p/pngj/
    (For JavaDocs, scroll down.)
- JDOM 2.x
    http://www.jdom.org/
    (For JavaDocs, look left.)
- SLF4J
    https://www.slf4j.org/
    (For JavaDocs, look left.)
- Logback
    https://logback.qos.ch/
    (For JavaDocs, look left.)
- picocli 2.x
    http://picocli.info/
    (For JavaDocs, look left and scroll down to "API Javadoc".)



Here's a batch file that builds when double-clicked (edit the vars).
- - - -
@ECHO OFF
SETLOCAL

SET JAVA_HOME=D:\Apps\j2sdk1.6.0_45
SET M2_HOME=D:\Apps\Maven

SET M2=%M2_HOME%\bin
SET PATH=%M2%;%PATH%

CD /D "%~dp0"
CALL mvn clean package

PAUSE
ENDLOCAL & EXIT /B
- - - -
