/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.passes;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.passes.htmlmatcher.ActiveEdge;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherAccumulatorNode;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherBlockNode;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherConditionNode;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherGraph;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherGraphNode;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherGraphNode.EdgeKind;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherTagNode;
import com.google.template.soy.passes.htmlmatcher.HtmlTagMatchingPass;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.AutoescapeMode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.ForIfemptyNode;
import com.google.template.soy.soytree.ForNonemptyNode;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.IfCondNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.MsgPluralCaseNode;
import com.google.template.soy.soytree.MsgPluralDefaultNode;
import com.google.template.soy.soytree.MsgSelectCaseNode;
import com.google.template.soy.soytree.MsgSelectDefaultNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.BlockNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.SwitchCaseNode;
import com.google.template.soy.soytree.SwitchNode;
import com.google.template.soy.soytree.TagName;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.VeLogNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A {@link CompilerFilePass} that checks strict html mode. See go/soy-html for usages.
 *
 * <p>Note: This pass requires that the {@link SoyConformancePass} has already been run.
 *
 * <p>TODO(b/118396161): Implement this pass, then replace {@link StrictHtmlValidationPass} with
 * this one.
 */
public final class StrictHtmlValidationPassNewMatcher extends CompilerFilePass {
  private static final SoyErrorKind INVALID_SELF_CLOSING_TAG =
      SoyErrorKind.of("''{0}'' tag is not allowed to be self-closing.");
  private static final SoyErrorKind VELOG_NODE_FIRST_CHILD_NOT_TAG =
      SoyErrorKind.of("The first child of '{velog'} must be a HTML open tag.");
  private static final SoyErrorKind VELOG_NODE_LAST_CHILD_NOT_TAG =
      SoyErrorKind.of("The last child of '{velog'} must be a HTML close tag.");
  private static final SoyErrorKind VELOG_NODE_EXACTLY_ONE_TAG =
      SoyErrorKind.of("'{velog'} must contain exactly one top-level HTML element.");

  private final ErrorReporter errorReporter;

  @Nullable private HtmlMatcherGraph htmlMatcherGraph = null;

  public StrictHtmlValidationPassNewMatcher(ErrorReporter errorReporter) {
    this.errorReporter = checkNotNull(errorReporter, "errorReporter must be non-null.");
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    for (TemplateNode node : file.getChildren()) {
      checkTemplateNode(node);
    }
  }

  private void checkTemplateNode(TemplateNode node) {
    AutoescapeMode autoescapeMode = node.getAutoescapeMode();
    // The SoyConformance pass runs before this pass, which guarantees that any strict HTML node has
    // STRICT autoescaping mode. Note that you are allowed to set STRICT autoescaping mode on
    // a non-strict-HTML node.
    checkState(
        autoescapeMode.equals(AutoescapeMode.STRICT) || !node.isStrictHtml(),
        "Strict HTML template without strict autoescaping.");
    // ContentKind is guaranteed to be non-null if AutoescapeMode is strict.
    if (node.isStrictHtml()) {
      htmlMatcherGraph = new HtmlTagVisitor(errorReporter).exec(node);
      if (!htmlMatcherGraph.getRootNode().isPresent()) {
        return;
      }
      HtmlTagMatchingPass.checkForErrors(
          new HtmlTagMatchingPass().visit(htmlMatcherGraph.getRootNode().get()), errorReporter);
      for (VeLogNode veNode : SoyTreeUtils.getAllNodesOfType(node, VeLogNode.class)) {
        checkVeLogNode(veNode);
      }
    }
  }

  @VisibleForTesting
  public Optional<HtmlMatcherGraph> getHtmlMatcherGraph() {
    return Optional.fromNullable(htmlMatcherGraph);
  }

  private void checkVeLogNode(VeLogNode node) {
    // {velog} cannot be empty.
    if (node.numChildren() == 0) {
      errorReporter.report(node.getSourceLocation(), VELOG_NODE_EXACTLY_ONE_TAG);
      return;
    }
    HtmlOpenTagNode firstTag = node.getOpenTagNode();
    // The first child of {velog} must be an open tag.
    if (firstTag == null) {
      errorReporter.report(node.getChild(0).getSourceLocation(), VELOG_NODE_FIRST_CHILD_NOT_TAG);
      return;
    }

    // If the first child is self-closing or is a void tag, reports an error if we see anything
    // after it. If it is the only thing, the velog is valid.
    if (firstTag.isSelfClosing() || firstTag.getTagName().isDefinitelyVoid()) {
      if (node.numChildren() > 1) {
        errorReporter.report(node.getChild(1).getSourceLocation(), VELOG_NODE_EXACTLY_ONE_TAG);
      }
      return;
    }

    SoyNode lastChild = node.getChild(node.numChildren() - 1);
    HtmlCloseTagNode lastTag = null;
    lastTag = node.getCloseTagNode();
    // The last child must be a close tag.
    if (lastTag == null) {
      errorReporter.report(lastChild.getSourceLocation(), VELOG_NODE_LAST_CHILD_NOT_TAG);
      return;
    }
    // This check make sures that there is exactly one top-level element -- the last tag must
    // close the first tag within {velog} command.
    if (lastTag.getTaggedPairs().size() != 1
        || !Objects.equals(lastTag.getTaggedPairs().get(0), firstTag)) {
      errorReporter.report(node.getChild(1).getSourceLocation(), VELOG_NODE_EXACTLY_ONE_TAG);
    }
  }

  private static final class HtmlTagVisitor extends AbstractSoyNodeVisitor<HtmlMatcherGraph> {

    private final HtmlMatcherGraph htmlMatcherGraph = new HtmlMatcherGraph();

    /**
     * A stack of active edge lists.
     *
     * <p>The active edges belong to the syntactically last HTML tags in a condition block. Note
     * that the syntactically last node might be the condition node itself, if there are no HTML
     * tags in its block. For example {@code {if $cond1}Content{/if}}.
     *
     * <p>At the end of each {@link IfNode}, all active edges are accumulated into a synthetic
     * {@link HtmlMatcherAccumulatorNode}. These synthetic nodes act as a pass-through node when
     * traversing the {@link HtmlMatcherGraph} in order to match HTML tags.
     *
     * <p>The stack is pushed on entry to an {@link IfNode} and popped at the end.
     */
    private final ArrayDeque<List<ActiveEdge>> activeEdgeStack = new ArrayDeque<>();

    private final ErrorReporter errorReporter;

    HtmlTagVisitor(ErrorReporter errorReporter) {
      this.errorReporter = errorReporter;
    }

    @Override
    public HtmlMatcherGraph exec(SoyNode node) {
      visitChildren((BlockNode) node);
      return htmlMatcherGraph;
    }

    @Override
    protected void visitHtmlOpenTagNode(HtmlOpenTagNode node) {
      TagName openTag = node.getTagName();
      // For static tag, check if it is a valid self-closing tag.
      if (openTag.isStatic()) {
        // Report errors for non-void tags that are self-closing.
        // For void tags, we don't care if they are self-closing or not. But when we visit
        // a HtmlCloseTagNode we will throw an error if it is a void tag.
        // Ignore this check if we are currently in a foreign content (svg).
        if (!openTag.isDefinitelyVoid() && node.isSelfClosing()) {
          errorReporter.report(
              node.getSourceLocation(), INVALID_SELF_CLOSING_TAG, openTag.getStaticTagName());
          return;
        }
      }
      // Push the node into open tag stack.
      if (!node.isSelfClosing() && !openTag.isDefinitelyVoid()) {
        htmlMatcherGraph.addNode(new HtmlMatcherTagNode(node));
      }
    }

    @Override
    protected void visitHtmlCloseTagNode(HtmlCloseTagNode node) {
      htmlMatcherGraph.addNode(new HtmlMatcherTagNode(node));
    }

    @Override
    protected void visitIfNode(IfNode node) {
      enterConditionalContext();
      visitChildren(node);
      exitConditionalContext();
    }

    @Override
    protected void visitIfCondNode(IfCondNode node) {
      HtmlMatcherConditionNode conditionNode = enterConditionBranch(node.getExpr(), node);
      visitChildren(node);
      exitConditionBranch(conditionNode);
    }

    @Override
    protected void visitSwitchNode(SwitchNode node) {
      enterConditionalContext();
      visitChildren(node);
      exitConditionalContext();
    }

    @Override
    protected void visitSwitchCaseNode(SwitchCaseNode node) {
      for (ExprNode expr : node.getExprList()) {
        HtmlMatcherConditionNode conditionNode = enterConditionBranch(expr, node);
        visitChildren(node);
        exitConditionBranch(conditionNode);
      }
    }

    // These are all the 'block' nodes.
    //
    // We require that every one of these blocks is internally balanced, to do that we recursively
    // call into ourselves to build a new independent graph.

    @Override
    protected void visitMsgNode(MsgNode node) {
      htmlMatcherGraph.addNode(
          new HtmlMatcherBlockNode(new HtmlTagVisitor(errorReporter).exec(node)));
    }

    @Override
    protected void visitMsgPluralCaseNode(MsgPluralCaseNode node) {
      htmlMatcherGraph.addNode(
          new HtmlMatcherBlockNode(new HtmlTagVisitor(errorReporter).exec(node)));
    }

    @Override
    protected void visitMsgPluralDefaultNode(MsgPluralDefaultNode node) {
      htmlMatcherGraph.addNode(
          new HtmlMatcherBlockNode(new HtmlTagVisitor(errorReporter).exec(node)));
    }

    @Override
    protected void visitMsgSelectCaseNode(MsgSelectCaseNode node) {
      htmlMatcherGraph.addNode(
          new HtmlMatcherBlockNode(new HtmlTagVisitor(errorReporter).exec(node)));
    }

    @Override
    protected void visitMsgSelectDefaultNode(MsgSelectDefaultNode node) {
      htmlMatcherGraph.addNode(
          new HtmlMatcherBlockNode(new HtmlTagVisitor(errorReporter).exec(node)));
    }

    @Override
    protected void visitCallParamContentNode(CallParamContentNode node) {
      htmlMatcherGraph.addNode(
          new HtmlMatcherBlockNode(new HtmlTagVisitor(errorReporter).exec(node)));
    }

    @Override
    protected void visitForIfemptyNode(ForIfemptyNode node) {
      htmlMatcherGraph.addNode(
          new HtmlMatcherBlockNode(new HtmlTagVisitor(errorReporter).exec(node)));
    }

    @Override
    protected void visitForNonemptyNode(ForNonemptyNode node) {
      htmlMatcherGraph.addNode(
          new HtmlMatcherBlockNode(new HtmlTagVisitor(errorReporter).exec(node)));
    }

    private void enterConditionalContext() {
      activeEdgeStack.push(new ArrayList<>());
    }

    private void exitConditionalContext() {
      // Add the syntactically last AST node of the else branch. If there is no else branch, then
      // add the syntactically last if branch. Note that the active edge of the syntactically last
      // if branch is the FALSE edge.
      List<ActiveEdge> activeEdges = activeEdgeStack.pop();
      if (htmlMatcherGraph.getNodeAtCursor().isPresent()) {
        HtmlMatcherGraphNode activeNode = htmlMatcherGraph.getNodeAtCursor().get();
        activeEdges.add(ActiveEdge.create(activeNode, activeNode.getActiveEdgeKind()));
      }
      HtmlMatcherAccumulatorNode accNode = new HtmlMatcherAccumulatorNode();
      accNode.accumulateActiveEdges(ImmutableList.copyOf(activeEdges));
      htmlMatcherGraph.addNode(accNode);
    }

    private HtmlMatcherConditionNode enterConditionBranch(ExprNode expr, SoyNode node) {
      HtmlMatcherConditionNode conditionNode = new HtmlMatcherConditionNode(node, expr);
      htmlMatcherGraph.addNode(conditionNode);
      htmlMatcherGraph.saveCursor();
      conditionNode.setActiveEdgeKind(EdgeKind.TRUE_EDGE);
      return conditionNode;
    }

    private void exitConditionBranch(HtmlMatcherConditionNode ifConditionNode) {
      // The graph cursor points to the syntactically last HTML tag in the if block. Note that this
      // could be the originating HtmlMatcherConditionNode.
      if (htmlMatcherGraph.getNodeAtCursor().isPresent()) {
        HtmlMatcherGraphNode activeNode = htmlMatcherGraph.getNodeAtCursor().get();
        activeEdgeStack.peek().add(ActiveEdge.create(activeNode, activeNode.getActiveEdgeKind()));
      }
      ifConditionNode.setActiveEdgeKind(EdgeKind.FALSE_EDGE);
      htmlMatcherGraph.restoreCursor();
    }

    @Override
    protected void visitSoyNode(SoyNode node) {
      if (node instanceof ParentSoyNode) {
        visitChildren((ParentSoyNode<?>) node);
      }
    }
  }
}
