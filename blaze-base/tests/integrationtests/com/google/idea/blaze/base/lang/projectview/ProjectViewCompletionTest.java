/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.lang.projectview;

import com.google.common.base.Joiner;
import com.google.idea.blaze.base.model.primitives.LanguageClass;
import com.google.idea.blaze.base.model.primitives.WorkspaceType;
import com.google.idea.blaze.base.projectview.section.SectionParser;
import com.google.idea.blaze.base.projectview.section.sections.Sections;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.psi.PsiFile;

import java.util.Arrays;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;

/**
 * Tests auto-complete in project view files
 */
public class ProjectViewCompletionTest extends ProjectViewIntegrationTestCase {

  private PsiFile setInput(String... fileContents) {
    return testFixture.configureByText(".blazeproject", Joiner.on("\n").join(fileContents));
  }

  private void assertResult(String... resultingFileContents) {
    String s = testFixture.getFile().getText();
    testFixture.checkResult(Joiner.on("\n").join(resultingFileContents));
  }

  public void testSectionTypeKeywords() {
    setInput(
      "<caret>");
    String[] keywords = getCompletionItemsAsStrings();

    assertThat(keywords).asList().containsAllIn(
      Sections.getUndeprecatedParsers().stream().map(SectionParser::getName).collect(Collectors.toList()));
  }

  public void testColonAndNewLineAndIndentInsertedAfterListSection() {
    setInput(
      "direc<caret>");
    assertThat(completeIfUnique()).isTrue();
    assertResult(
      "directories:",
      "  <caret>");
  }

  public void testWhitespaceDividerInsertedAfterScalarSection() {
    setInput(
      "impo<caret>");

    LookupElement[] completionItems = testFixture.completeBasic();
    assertThat(completionItems[0].getLookupString()).isEqualTo("import");

    testFixture.getLookup().setCurrentItem(completionItems[0]);
    testFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

    assertResult(
      "import <caret>");
  }

  public void testColonDividerAndSpaceInsertedAfterScalarSection() {
    setInput(
      "works<caret>");
    assertThat(completeIfUnique()).isTrue();
    assertResult(
      "workspace_type: <caret>");
  }

  public void testNoKeywordCompletionInListItem() {
    setInput(
      "directories:",
      "  <caret>");

    String[] completionItems = getCompletionItemsAsStrings();
    if (completionItems == null) {
      fail("Spurious completion. New file contents: " + testFixture.getFile().getText());
    }
    assertThat(completionItems).isEmpty();
  }

  public void testNoKeywordCompletionAfterKeyword() {
    setInput(
      "import <caret>");

    String[] completionItems = getCompletionItemsAsStrings();
    if (completionItems == null) {
      fail("Spurious completion. New file contents: " + testFixture.getFile().getText());
    }
    assertThat(completionItems).isEmpty();
  }

  public void testWorkspaceTypeCompletion() {
    setInput(
      "workspace_type: <caret>");

    String[] types = getCompletionItemsAsStrings();

    assertThat(types).asList().containsAllIn(
      Arrays.stream(WorkspaceType.values()).map(WorkspaceType::getName).collect(Collectors.toList()));
  }

  public void testAdditionalLanguagesCompletion() {
    setInput(
      "additional_languages:",
      "  <caret>");

    String[] types = getCompletionItemsAsStrings();

    assertThat(types).asList().containsAllIn(
      Arrays.stream(LanguageClass.values()).map(LanguageClass::getName).collect(Collectors.toList()));
  }

  public void testUniqueDirectoryCompleted() {
    setInput(
      "import <caret>");

    createDirectory("java");

    String[] completionItems = getCompletionItemsAsStrings();
    assertThat(completionItems).isNull();
    assertResult(
      "import java<caret>"
    );
  }

  public void testUniqueMultiSegmentDirectoryCompleted() {
    setInput(
      "import <caret>");

    createDirectory("java/com/google");

    String[] completionItems = getCompletionItemsAsStrings();
    assertThat(completionItems).isNull();
    assertResult(
      "import java/com/google<caret>"
    );
  }

  public void testNonDirectoriesIgnored() {
    setInput(
      "import <caret>");

    createDirectory("java/com/google");
    createFile("java/IgnoredFile.java");

    String[] completionItems = getCompletionItemsAsStrings();
    assertThat(completionItems).isNull();
    assertResult(
      "import java/com/google<caret>"
    );
  }

  public void testMultipleDirectoryOptions() {
    createDirectory("foo");
    createDirectory("bar");
    createDirectory("other");
    createDirectory("ostrich/foo");
    createDirectory("ostrich/fooz");

    setInput(
      "targets:",
      "  //o<caret>");

    String[] completionItems = getCompletionItemsAsSuggestionStrings();
    assertThat(completionItems).asList().containsExactly("other", "ostrich");

    performTypingAction(testFixture.getEditor(), 's');

    completionItems = getCompletionItemsAsStrings();
    assertThat(completionItems).isNull();
    assertResult(
      "targets:",
      "  //ostrich<caret>");
  }

  public void testRuleCompletion() {
    createFile(
      "BUILD",
      "java_library(name = 'lib')"
    );

    setInput(
      "targets:",
      "  //:<caret>");

    String[] completionItems = getCompletionItemsAsSuggestionStrings();
    assertThat(completionItems).isNull();
    assertResult(
      "targets:",
      "  //:lib<caret>");
  }

}