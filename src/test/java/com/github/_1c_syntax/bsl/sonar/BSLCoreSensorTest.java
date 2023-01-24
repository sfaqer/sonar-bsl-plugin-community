/*
 * This file is a part of SonarQube 1C (BSL) Community Plugin.
 *
 * Copyright (c) 2018-2023
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

import com.github._1c_syntax.bsl.languageserver.configuration.Language;
import com.github._1c_syntax.bsl.sonar.language.BSLLanguage;
import com.github._1c_syntax.bsl.sonar.language.BSLLanguageServerRuleDefinition;
import org.junit.jupiter.api.Test;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.batch.rule.internal.ActiveRulesBuilder;
import org.sonar.api.batch.rule.internal.NewActiveRule;
import org.sonar.api.batch.sensor.internal.DefaultSensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.internal.SonarRuntimeImpl;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.FileLinesContext;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.Version;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BSLCoreSensorTest {

  private final String BASE_PATH = "src/test/resources/examples";
  private final File BASE_DIR = new File(BASE_PATH).getAbsoluteFile();
  private final String FILE_NAME = "src/test.bsl";
  private final Version SONAR_VERSION = Version.create(7, 9);
  private final SensorContextTester context = createSensorContext();

  @Test
  void testDescriptor() {
    var fileLinesContextFactory = mock(FileLinesContextFactory.class);

    var sensor = new BSLCoreSensor(context, fileLinesContextFactory);
    var sensorDescriptor = new DefaultSensorDescriptor();
    sensor.describe(sensorDescriptor);

    assertThat(sensorDescriptor.name()).isEqualTo("BSL Core Sensor");
    assertThat(sensorDescriptor.languages().toArray()[0]).isEqualTo(BSLLanguage.KEY);
  }

  @Test
  void testExecute() {

    var diagnosticName = "OneStatementPerLine";
    var ruleKey = RuleKey.of(BSLLanguageServerRuleDefinition.REPOSITORY_KEY, diagnosticName);

    SensorContextTester context;
    BSLCoreSensor sensor;

    // Mock visitor for metrics.
    var fileLinesContext = mock(FileLinesContext.class);
    var fileLinesContextFactory = mock(FileLinesContextFactory.class);
    when(fileLinesContextFactory.createFor(any(InputFile.class))).thenReturn(fileLinesContext);

    context = createSensorContext();
    setActiveRules(context, diagnosticName, ruleKey);
    sensor = new BSLCoreSensor(context, fileLinesContextFactory);
    sensor.execute(context);

    assertThat(context.isCancelled()).isFalse();

    context = createSensorContext();
    setActiveRules(context, diagnosticName, ruleKey);
    context.settings().setProperty(BSLCommunityProperties.LANG_SERVER_ENABLED_KEY, false);
    sensor = new BSLCoreSensor(context, fileLinesContextFactory);
    sensor.execute(context);

    assertThat(context.isCancelled()).isFalse();

    context = createSensorContext();
    setActiveRules(context, diagnosticName, ruleKey);
    context.settings().setProperty(
      BSLCommunityProperties.LANG_SERVER_DIAGNOSTIC_LANGUAGE_KEY, Language.EN.getLanguageCode());
    sensor = new BSLCoreSensor(context, fileLinesContextFactory);
    sensor.execute(context);

    assertThat(context.isCancelled()).isFalse();

    context = createSensorContext();
    setActiveRules(context, diagnosticName, ruleKey);
    context.settings().setProperty(
      BSLCommunityProperties.LANG_SERVER_OVERRIDE_CONFIGURATION_KEY, Boolean.TRUE.toString());
    context.settings().setProperty(
      BSLCommunityProperties.LANG_SERVER_CONFIGURATION_PATH_KEY,
      Path.of(BASE_PATH, ".bsl-language-server.json").toString());
    sensor = new BSLCoreSensor(context, fileLinesContextFactory);
    sensor.execute(context);

    assertThat(context.isCancelled()).isFalse();

    context = createSensorContext();
    setActiveRules(context, diagnosticName, ruleKey);
    context.settings().setProperty(
      BSLCommunityProperties.LANG_SERVER_OVERRIDE_CONFIGURATION_KEY, Boolean.TRUE.toString());
    context.settings().setProperty(BSLCommunityProperties.LANG_SERVER_CONFIGURATION_PATH_KEY, "fake.file");
    sensor = new BSLCoreSensor(context, fileLinesContextFactory);
    sensor.execute(context);

    assertThat(context.isCancelled()).isFalse();

  }

  @Test
  void testExecuteCastDiagnosticParameterValue() {

    var diagnosticEmptyCodeBlock = "EmptyCodeBlock";
    var diagnosticCommentedCode = "CommentedCode";
    var diagnosticLineLength = "LineLength";
    var diagnosticUsingHardcodeNetworkAddress = "UsingHardcodeNetworkAddress";

    var fileLinesContext = mock(FileLinesContext.class);
    var fileLinesContextFactory = mock(FileLinesContextFactory.class);
    when(fileLinesContextFactory.createFor(any(InputFile.class))).thenReturn(fileLinesContext);

    var context = createSensorContext();
    ActiveRules activeRules = new ActiveRulesBuilder()
      .addRule(newActiveRule(diagnosticEmptyCodeBlock, "commentAsCode", "true"))
      .addRule(newActiveRule(diagnosticCommentedCode, "threshold", "0.9F"))
      .addRule(newActiveRule(diagnosticLineLength, "maxLineLength", "100"))
      .addRule(
        newActiveRule(
          diagnosticUsingHardcodeNetworkAddress,
          "searchWordsExclusion",
          "Верси|Version"))
      .build();
    context.setActiveRules(activeRules);

    var sensor = new BSLCoreSensor(context, fileLinesContextFactory);
    sensor.execute(context);

    assertThat(context.isCancelled()).isFalse();
  }

  @Test
  void testExecuteCoverage() {
    var diagnosticName = "OneStatementPerLine";
    var ruleKey = RuleKey.of(BSLLanguageServerRuleDefinition.REPOSITORY_KEY, diagnosticName);

    SensorContextTester context;
    BSLCoreSensor sensor;

    // Mock visitor for metrics.
    var fileLinesContext = mock(FileLinesContext.class);
    var fileLinesContextFactory = mock(FileLinesContextFactory.class);
    when(fileLinesContextFactory.createFor(any(InputFile.class))).thenReturn(fileLinesContext);

    context = createSensorContext();
    context.settings().setProperty(BSLCommunityProperties.LANG_SERVER_ENABLED_KEY, false);
    setActiveRules(context, diagnosticName, ruleKey);
    sensor = new BSLCoreSensor(context, fileLinesContextFactory);
    sensor.execute(context);

    assertThat(context.isCancelled()).isFalse();
  }

  @Test
  void testComplexity() {
    var diagnosticCyclomaticComplexity = "CyclomaticComplexity";
    var diagnosticCognitiveComplexity = "CognitiveComplexity";

    var context = createSensorContext();
    var activeRules = new ActiveRulesBuilder()
      .addRule(newActiveRule(diagnosticCyclomaticComplexity))
      .addRule(newActiveRule(diagnosticCognitiveComplexity))
      .build();
    context.setActiveRules(activeRules);

    var fileLinesContext = mock(FileLinesContext.class);
    var fileLinesContextFactory = mock(FileLinesContextFactory.class);
    when(fileLinesContextFactory.createFor(any(InputFile.class))).thenReturn(fileLinesContext);

    BSLCoreSensor sensor = new BSLCoreSensor(context, fileLinesContextFactory);

    sensor.execute(context);

    // todo надо как-то нормально ключ компонента определить
    var componentKey = "moduleKey:" + FILE_NAME;
    assertThat(context.measures(componentKey)).isNotEmpty();
    assertThat(context.measure(componentKey, CoreMetrics.COMPLEXITY).value()).isEqualTo(3);
    assertThat(context.measure(componentKey, CoreMetrics.COGNITIVE_COMPLEXITY).value()).isEqualTo(1);

  }

  @Test
  void testCPD() {
    var diagnosticName = "OneStatementPerLine";
    var ruleKey = RuleKey.of(BSLLanguageServerRuleDefinition.REPOSITORY_KEY, diagnosticName);

    // Mock visitor for metrics.
    var fileLinesContext = mock(FileLinesContext.class);
    var fileLinesContextFactory = mock(FileLinesContextFactory.class);
    when(fileLinesContextFactory.createFor(any(InputFile.class))).thenReturn(fileLinesContext);

    var context = createSensorContext();

    var sensor = new BSLCoreSensor(context, fileLinesContextFactory);
    sensor.execute(context);

    var componentKey = "moduleKey:" + FILE_NAME;
    assertThat(context.cpdTokens(componentKey))
            .isNotNull()
            .hasSize(13);
    assertThat(context.cpdTokens(componentKey))
            .filteredOn( tok -> tok.getValue().startsWith("ОставшийсяТокен"))
            .hasSize(1);
    assertThat(context.cpdTokens(componentKey))
            .filteredOn( tok -> tok.getValue().startsWith("ПропущенныйТокен"))
            .isEmpty();

  }

  private void setActiveRules(SensorContextTester context, String diagnosticName, RuleKey ruleKey) {
    var activeRules = new ActiveRulesBuilder()
      .addRule(new NewActiveRule.Builder()
        .setRuleKey(ruleKey)
        .setName(diagnosticName)
        .build())
      .build();
    context.setActiveRules(activeRules);
  }

  private NewActiveRule newActiveRule(String diagnosticName, String key, String value) {
    return new NewActiveRule.Builder()
      .setRuleKey(RuleKey.of(BSLLanguageServerRuleDefinition.REPOSITORY_KEY, diagnosticName))
      .setName(diagnosticName)
      .setParam(key, value)
      .build();
  }

  private NewActiveRule newActiveRule(String diagnosticName) {
    return new NewActiveRule.Builder()
      .setRuleKey(RuleKey.of(BSLLanguageServerRuleDefinition.REPOSITORY_KEY, diagnosticName))
      .setName(diagnosticName)
      .build();
  }

  private SensorContextTester createSensorContext() {
    var sonarRuntime = SonarRuntimeImpl.forSonarLint(SONAR_VERSION);
    var context = SensorContextTester.create(BASE_DIR);
    context.fileSystem().setEncoding(StandardCharsets.UTF_8);
    context.setRuntime(sonarRuntime);
    context.settings().setProperty("sonar.sources", "src");
    context.settings().setProperty("sonar.tests", "test");

    var inputFile = Tools.inputFileBSL(FILE_NAME, BASE_DIR);
    context.fileSystem().add(inputFile);

    return context;
  }

}
