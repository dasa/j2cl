/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.common;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** An error logger class that records the number of errors and provides error print methods. */
public class Problems {

  /** Represents compiler problem categories. */
  public enum Message {
    ERR_FLAG_FILE("Cannot load flag file: %s.", 1),
    ERR_FILE_NOT_FOUND("File '%s' not found.", 1),
    ERR_UNKNOWN_INPUT_TYPE("Cannot recognize input type for file '%s'.", 1),
    ERR_OUTPUT_LOCATION("Output location '%s' must be a directory or .zip file.", 1),
    ERR_CANNOT_EXTRACT_ZIP("Cannot extract zip '%s'.", 1),
    ERR_CANNOT_OPEN_ZIP("Cannot open zip '%s': %s.", 2),
    ERR_CANNOT_CLOSE_ZIP("Cannot close zip: %s.", 1),
    ERR_NATIVE_JAVA_SOURCE_NO_MATCH("Cannot find matching native file '%s'.", 1),
    ERR_NATIVE_UNUSED_NATIVE_SOURCE("Native JavaScript file '%s' not used.", 1),
    ERR_CANNOT_CREATE_TEMP_DIR("Cannot create temporary directory: %s.", 1),
    ERR_CANNOT_OPEN_FILE("Cannot open file: %s.", 1),
    ERR_PACKAGE_INFO_PARSE("Resource '%s' was found but it failed to parse.", 1),
    ERR_CLASS_PATH_URL("Class path entry '%s' is not a valid url.", 1),
    ERR_GWT_INCOMPATIBLE_FOUND_IN_COMPILE(
        "@GwtIncompatible annotations found in %s "
            + "Please run this library through the @GwtIncompatible stripper tool.",
        1),
    ;

    // used for customized message.
    private final String message;
    // number of arguments the message takes.
    private final int numberOfArguments;

    Message(String message, int numberOfArguments) {
      this.message = message;
      this.numberOfArguments = numberOfArguments;
    }

    public String getMessage() {
      return message;
    }

    private int getNumberOfArguments() {
      return numberOfArguments;
    }
  }

  /** Represents the severity of the problem */
  public enum Severity {
    ERROR("Error"),
    WARNING("Warning"),
    INFO("Info");

    Severity(String messagePrefix) {
      this.messagePrefix = messagePrefix;
    }

    private final String messagePrefix;

    public String getMessagePrefix() {
      return messagePrefix;
    }
  }

  private boolean abortRequested = false;
  private final Multimap<Severity, String> problemsBySeverity = LinkedHashMultimap.create();

  public void error(Message message) {
    abortWhenPossible();
    problemsBySeverity.put(Severity.ERROR, message.getMessage());
  }

  public void error(SourcePosition sourcePosition, String detailMessage, Object... args) {
    problem(Severity.ERROR, sourcePosition, detailMessage, args);
  }

  public void error(int lineNumber, String filePath, String detailMessage, Object... args) {
    problem(Severity.ERROR, lineNumber, filePath, detailMessage, args);
  }

  public void warning(SourcePosition sourcePosition, String detailMessage, Object... args) {
    problem(Severity.WARNING, sourcePosition, detailMessage, args);
  }

  private void problem(
      Severity severity, SourcePosition sourcePosition, String detailMessage, Object... args) {
    problem(
        severity,
        // SourcePosition lines are 0 based.
        sourcePosition.getStartFilePosition().getLine() + 1,
        sourcePosition.getFilePath(),
        detailMessage,
        args);
  }

  private void problem(
      Severity severity, int lineNumber, String filePath, String detailMessage, Object... args) {
    if (severity == Severity.ERROR) {
      abortWhenPossible();
    }
    String message = args.length == 0 ? detailMessage : J2clUtils.format(detailMessage, args);
    problemsBySeverity.put(
        severity,
        J2clUtils.format(
            "%s:%s:%s: %s",
            severity.getMessagePrefix(),
            // Only report the file name portion to be consistent with JDT reported problems.
            filePath.substring(filePath.lastIndexOf('/') + 1),
            lineNumber,
            message));
  }

  public void error(String detailMessage, Object... args) {
    abortWhenPossible();
    problemsBySeverity.put(Severity.ERROR, "Error: " + J2clUtils.format(detailMessage, args));
  }

  public void error(Message message, Object... args) {
    checkArgument(message.getNumberOfArguments() == args.length);
    abortWhenPossible();
    problemsBySeverity.put(
        Severity.ERROR, "Error: " + J2clUtils.format(message.getMessage(), args));
  }

  public void warning(String detailMessage, Object... args) {
    problemsBySeverity.put(Severity.WARNING, J2clUtils.format(detailMessage, args));
  }

  public void info(String detailMessage, Object... args) {
    problemsBySeverity.put(Severity.INFO, J2clUtils.format(detailMessage, args));
  }

  public void abortWhenPossible() {
    abortRequested = true;
  }

  /** Prints all problems to provided output and returns the exit code. */
  public int reportAndGetExitCode(PrintStream output) {
    return reportAndGetExitCode(new PrintWriter(output, true));
  }

  /** Prints all problems to provided output and returns the exit code. */
  public int reportAndGetExitCode(PrintWriter output) {
    for (Map.Entry<Severity, String> severityMessagePair : problemsBySeverity.entries()) {
      output.println(severityMessagePair.getValue());
    }
    if (hasErrors() || hasWarnings()) {
      J2clUtils.printf(
          output,
          "%d error(s), %d warning(s).\n",
          problemsBySeverity.get(Severity.ERROR).size(),
          problemsBySeverity.get(Severity.WARNING).size());
    }

    return hasErrors() ? 1 : 0;
  }

  public boolean hasWarnings() {
    return problemsBySeverity.containsKey(Severity.WARNING);
  }

  public boolean hasErrors() {
    return problemsBySeverity.containsKey(Severity.ERROR);
  }

  public boolean hasProblems() {
    return !problemsBySeverity.isEmpty();
  }

  /** If there were errors abort. */
  public void abortIfRequested() {
    if (abortRequested) {
      throw new Exit();
    }
  }

  public List<String> getErrors() {
    return getMessages(Severity.ERROR);
  }

  public List<String> getWarnings() {
    return getMessages(Severity.WARNING);
  }

  public List<String> getInfoMessages() {
    return getMessages(Severity.INFO);
  }

  public List<String> getMessages() {
    return getMessages(EnumSet.allOf(Severity.class));
  }

  private List<String> getMessages(Severity severity) {
    return getMessages(Collections.singleton(severity));
  }

  private List<String> getMessages(Collection<Severity> severities) {
    return problemsBySeverity
        .entries()
        .stream()
        .filter(e -> severities.contains(e.getKey()))
        .map(Map.Entry::getValue)
        .collect(Collectors.toList());
  }

  /**
   * Exit is thrown to signal that a System.exit should be performed at a higher level.
   *
   * <p>Note: It should never be caught except on the top level.
   */
  public static class Exit extends java.lang.Error {}
}
