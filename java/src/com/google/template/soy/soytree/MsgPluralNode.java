/*
 * Copyright 2010 Google Inc.
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

package com.google.template.soy.soytree;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.exprtree.ExprEquivalence;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.soytree.CommandTagAttribute.CommandTagAttributesHolder;
import com.google.template.soy.soytree.SoyNode.ExprHolderNode;
import com.google.template.soy.soytree.SoyNode.MsgSubstUnitNode;
import com.google.template.soy.soytree.SoyNode.SplitLevelTopNode;
import java.util.List;

/**
 * Node representing a 'plural' block.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 */
public final class MsgPluralNode extends AbstractParentCommandNode<CaseOrDefaultNode>
    implements MsgSubstUnitNode,
        SplitLevelTopNode<CaseOrDefaultNode>,
        ExprHolderNode,
        CommandTagAttributesHolder {

  /** Fallback base plural var name. */
  public static final String FALLBACK_BASE_PLURAL_VAR_NAME = "NUM";

  /** The location of the {plural ...} tag. */
  private final SourceLocation openTagLocation;

  private final List<CommandTagAttribute> attributes;

  /** The offset. */
  private final int offset;

  /** The parsed expression. */
  private final ExprRootNode pluralExpr;

  /** The base plural var name (what the translator sees). */
  private final String basePluralVarName;

  public MsgPluralNode(
      int id,
      SourceLocation location,
      SourceLocation openTagLocation,
      ExprNode expr,
      List<CommandTagAttribute> attributes,
      int offset) {
    super(id, location, "plural");
    this.openTagLocation = openTagLocation;
    this.pluralExpr = new ExprRootNode(expr);
    this.attributes = ImmutableList.copyOf(attributes);
    this.offset = offset;
    this.basePluralVarName =
        MsgSubstUnitBaseVarNameUtils.genNaiveBaseNameForExpr(expr, FALLBACK_BASE_PLURAL_VAR_NAME);
  }

  /**
   * Copy constructor.
   *
   * @param orig The node to copy.
   */
  private MsgPluralNode(MsgPluralNode orig, CopyState copyState) {
    super(orig, copyState);
    this.openTagLocation = orig.openTagLocation;
    this.pluralExpr = orig.pluralExpr.copy(copyState);
    this.attributes =
        orig.attributes.stream().map(c -> c.copy(copyState)).collect(toImmutableList());
    this.offset = orig.offset;
    this.basePluralVarName = orig.basePluralVarName;
    copyState.updateRefs(orig, this);
  }

  /** The location of the {plural ...} tag. */
  @Override
  public SourceLocation getOpenTagLocation() {
    return this.openTagLocation;
  }

  @Override
  public List<CommandTagAttribute> getAttributes() {
    return this.attributes;
  }

  @Override
  public Kind getKind() {
    return Kind.MSG_PLURAL_NODE;
  }

  /** Returns the offset. */
  public int getOffset() {
    return offset;
  }

  /** Returns the parsed expression. */
  public ExprRootNode getExpr() {
    return pluralExpr;
  }

  /** Returns the base plural var name (what the translator sees). */
  @Override
  public String getBaseVarName() {
    return basePluralVarName;
  }

  @Override
  public boolean shouldUseSameVarNameAs(MsgSubstUnitNode other, ExprEquivalence exprEquivalence) {
    if (!(other instanceof MsgPluralNode)) {
      return false;
    }

    MsgPluralNode that = (MsgPluralNode) other;
    return exprEquivalence.equivalent(this.pluralExpr, that.pluralExpr)
        && this.offset == that.offset;
  }

  @Override
  public String getCommandText() {
    return (offset > 0)
        ? pluralExpr.toSourceString() + " offset=\"" + offset + "\""
        : pluralExpr.toSourceString();
  }

  @Override
  public ImmutableList<ExprRootNode> getExprList() {
    return ImmutableList.of(pluralExpr);
  }

  @Override
  public MsgBlockNode getParent() {
    return (MsgBlockNode) super.getParent();
  }

  @Override
  public MsgPluralNode copy(CopyState copyState) {
    return new MsgPluralNode(this, copyState);
  }
}
