/*
 * This file is a part of SonarQube 1C (BSL) Community Plugin.
 *
 * Copyright © 2018-2021
 * Alexey Sosnoviy <labotamy@gmail.com>, Nikita Fedkin <nixel2007@gmail.com>
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * SonarQube 1C (BSL) Community Plugin is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * SonarQube 1C (BSL) Community Plugin is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with SonarQube 1C (BSL) Community Plugin.
 */
package com.github._1c_syntax.bsl.sonar;

import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticCode;
import com.github._1c_syntax.bsl.sonar.acc.ACCProperties;
import com.github._1c_syntax.bsl.sonar.acc.ACCRuleDefinition;
import com.github._1c_syntax.bsl.sonar.language.BSLLanguage;
import com.github._1c_syntax.bsl.sonar.language.BSLLanguageServerRuleDefinition;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.sonar.api.batch.fs.FilePredicates;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.TextRange;
import org.sonar.api.batch.rule.Severity;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rules.RuleType;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

import javax.annotation.CheckForNull;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class IssuesLoader {

  private static final Logger LOGGER = Loggers.get(IssuesLoader.class);

  private final SensorContext context;
  private final Map<DiagnosticSeverity, Severity> severityMap;
  private final Map<DiagnosticSeverity, RuleType> ruleTypeMap;
  private final FileSystem fileSystem;
  private final FilePredicates predicates;
  private final boolean createExternalIssuesWithACCSources;

  public IssuesLoader(SensorContext context) {
    this.context = context;
    this.fileSystem = context.fileSystem();
    this.predicates = fileSystem.predicates();
    this.severityMap = createDiagnosticSeverityMap();
    this.ruleTypeMap = createRuleTypeMap();
    this.createExternalIssuesWithACCSources = context.config().getBoolean(ACCProperties.CREATE_EXTERNAL_ISSUES)
      .orElse(ACCProperties.CREATE_EXTERNAL_ISSUES_DEFAULT_VALUE);
  }

  public void createIssue(InputFile inputFile, Diagnostic diagnostic) {

    var needCreateExternalIssue = true;
    var ruleId = DiagnosticCode.getStringValue(diagnostic.getCode());
    String keyRepository = BSLLanguageServerRuleDefinition.REPOSITORY_KEY;

    if (isACCDiagnostic(diagnostic)) {
      needCreateExternalIssue = this.createExternalIssuesWithACCSources;
      keyRepository = ACCRuleDefinition.REPOSITORY_KEY;
    }

    var ruleKey = RuleKey.of(keyRepository, ruleId);
    var activeRule = context.activeRules().find(ruleKey);

    if (needCreateExternalIssue && activeRule == null) {
      createExternalIssue(inputFile, diagnostic);
      return;
    }

    var issue = context.newIssue();
    issue.forRule(ruleKey);

    Supplier<NewIssueLocation> newIssueLocationSupplier = issue::newLocation;
    Consumer<NewIssueLocation> newIssueAddLocationConsumer = issue::addLocation;
    Consumer<NewIssueLocation> newIssueAtConsumer = issue::at;

    processDiagnostic(
      inputFile,
      diagnostic,
      ruleId,
      newIssueLocationSupplier,
      newIssueAddLocationConsumer,
      newIssueAtConsumer
    );

    issue.save();
  }

  private void processDiagnostic(
    InputFile inputFile,
    Diagnostic diagnostic,
    String ruleId,
    Supplier<NewIssueLocation> newIssueLocationSupplier,
    Consumer<NewIssueLocation> newIssueAddLocationConsumer,
    Consumer<NewIssueLocation> newIssueAtConsumer
  ) {

    var textRange = getTextRange(inputFile, diagnostic.getRange(), ruleId);

    var location = newIssueLocationSupplier.get();
    location.on(inputFile);
    location.at(textRange);
    location.message(diagnostic.getMessage());

    newIssueAtConsumer.accept(location);

    List<DiagnosticRelatedInformation> relatedInformation = diagnostic.getRelatedInformation();
    if (relatedInformation != null) {
      relatedInformation.forEach(
        (DiagnosticRelatedInformation relatedInformationEntry) -> {
          var path = Paths.get(URI.create(relatedInformationEntry.getLocation().getUri())).toAbsolutePath();
          var relatedInputFile = getInputFile(path);
          if (relatedInputFile == null) {
            LOGGER.warn("Can't find inputFile for absolute path {}", path);
            return;
          }
          var relatedIssueLocation = newIssueLocationSupplier.get();

          var relatedTextRange = getTextRange(
            relatedInputFile,
            relatedInformationEntry.getLocation().getRange(),
            ruleId
          );

          relatedIssueLocation.on(relatedInputFile);
          relatedIssueLocation.at(relatedTextRange);
          relatedIssueLocation.message(relatedInformationEntry.getMessage());

          newIssueAddLocationConsumer.accept(relatedIssueLocation);
        }
      );
    }
  }

  private static boolean isACCDiagnostic(Diagnostic diagnostic) {
    return ACCRuleDefinition.SOURCE.equals(diagnostic.getSource());
  }

  private void createExternalIssue(InputFile inputFile, Diagnostic diagnostic) {
    var issue = context.newExternalIssue();

    String engineId;
    if (isACCDiagnostic(diagnostic)) {
      engineId = ACCRuleDefinition.SOURCE;
    } else {
      engineId = "bsl-language-server";
    }
    issue.engineId(engineId);

    var ruleId = DiagnosticCode.getStringValue(diagnostic.getCode());
    issue.ruleId(ruleId);

    issue.type(ruleTypeMap.get(diagnostic.getSeverity()));
    issue.severity(severityMap.get(diagnostic.getSeverity()));

    Supplier<NewIssueLocation> newIssueLocationSupplier = issue::newLocation;
    Consumer<NewIssueLocation> newIssueAddLocationConsumer = issue::addLocation;
    Consumer<NewIssueLocation> newIssueAtConsumer = issue::at;

    processDiagnostic(
      inputFile,
      diagnostic,
      ruleId,
      newIssueLocationSupplier,
      newIssueAddLocationConsumer,
      newIssueAtConsumer
    );

    issue.save();
  }

  @CheckForNull
  private InputFile getInputFile(Path path) {
    return fileSystem.inputFile(
      predicates.and(
        predicates.hasLanguage(BSLLanguage.KEY),
        predicates.hasAbsolutePath(path.toAbsolutePath().toString())
      )
    );
  }

  private static TextRange getTextRange(InputFile inputFile, Range range, String ruleKey) {
    Position start = range.getStart();
    Position end = range.getEnd();
    int startLine = start.getLine() + 1;

    TextRange textRange;

    try {
      textRange = inputFile.newRange(
        startLine,
        start.getCharacter(),
        end.getLine() + 1,
        end.getCharacter()
      );
    } catch (IllegalArgumentException e) {
      var formattedRange = String.format(
        "start(%d, %d), end(%d, %d)",
        range.getStart().getLine(),
        range.getStart().getCharacter(),
        range.getEnd().getLine(),
        range.getEnd().getCharacter()
      );
      LOGGER.error(
        "Can't compute TextRange for given Range: {} of rule: {} in file: {}.",
        formattedRange,
        ruleKey,
        inputFile.uri(),
        e
      );

      textRange = selectThisOrPreviousLine(inputFile, startLine);
    }

    return textRange;

  }

  private static TextRange selectThisOrPreviousLine(InputFile inputFile, int line) {
    int lines = inputFile.lines();

    if (line == 0) {
      return inputFile.newRange(1, 0, lines, 0);
    }

    if (lines >= line) {
      try {
        return inputFile.selectLine(line);
      } catch (IllegalArgumentException e) {
        LOGGER.error("Can't compute TextRange for given line {}", line, e);
        return selectThisOrPreviousLine(inputFile, line - 1);
      }
    } else {
      return selectThisOrPreviousLine(inputFile, lines);
    }
  }

  private static Map<DiagnosticSeverity, Severity> createDiagnosticSeverityMap() {
    Map<DiagnosticSeverity, Severity> map = new EnumMap<>(DiagnosticSeverity.class);
    map.put(DiagnosticSeverity.Warning, Severity.MAJOR);
    map.put(DiagnosticSeverity.Information, Severity.MINOR);
    map.put(DiagnosticSeverity.Hint, Severity.INFO);
    map.put(DiagnosticSeverity.Error, Severity.CRITICAL);

    return map;
  }

  private static Map<DiagnosticSeverity, RuleType> createRuleTypeMap() {
    Map<DiagnosticSeverity, RuleType> map = new EnumMap<>(DiagnosticSeverity.class);
    map.put(DiagnosticSeverity.Warning, RuleType.CODE_SMELL);
    map.put(DiagnosticSeverity.Information, RuleType.CODE_SMELL);
    map.put(DiagnosticSeverity.Hint, RuleType.CODE_SMELL);
    map.put(DiagnosticSeverity.Error, RuleType.BUG);

    return map;
  }
}
