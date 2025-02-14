/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.optimizer.calcite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelOptUtil.InputFinder;
import org.apache.calcite.plan.RelOptUtil.InputReferencedVisitor;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.RelFactories.ProjectFactory;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexLocalRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexRangeRef;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexVisitor;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.metadata.VirtualColumn;
import org.apache.hadoop.hive.ql.optimizer.calcite.reloperators.HiveMultiJoin;
import org.apache.hadoop.hive.ql.optimizer.calcite.reloperators.HiveProject;
import org.apache.hadoop.hive.ql.optimizer.calcite.translator.ExprNodeConverter;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.parse.ParseUtils;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Generic utility functions needed for Calcite based Hive CBO.
 */

public class HiveCalciteUtil {

  private static final Logger LOG = LoggerFactory.getLogger(HiveCalciteUtil.class);


  /**
   * Get list of virtual columns from the given list of projections.
   * <p>
   *
   * @param exps
   *          list of rex nodes representing projections
   * @return List of Virtual Columns, will not be null.
   */
  public static List<Integer> getVirtualCols(List<? extends RexNode> exps) {
    List<Integer> vCols = new ArrayList<Integer>();

    for (int i = 0; i < exps.size(); i++) {
      if (!(exps.get(i) instanceof RexInputRef)) {
        vCols.add(i);
      }
    }

    return vCols;
  }

  public static boolean validateASTForUnsupportedTokens(ASTNode ast) {
    if (ParseUtils.containsTokenOfType(ast, HiveParser.TOK_CHARSETLITERAL, HiveParser.TOK_TABLESPLITSAMPLE)) {
      return false;
    } else {
      return true;
    }
  }

  public static List<RexNode> getProjsFromBelowAsInputRef(final RelNode rel) {
    List<RexNode> projectList = Lists.transform(rel.getRowType().getFieldList(),
        new Function<RelDataTypeField, RexNode>() {
          @Override
          public RexNode apply(RelDataTypeField field) {
            return rel.getCluster().getRexBuilder().makeInputRef(field.getType(), field.getIndex());
          }
        });
    return projectList;
  }

  public static List<Integer> translateBitSetToProjIndx(ImmutableBitSet projBitSet) {
    List<Integer> projIndxLst = new ArrayList<Integer>();

    for (int i = 0; i < projBitSet.length(); i++) {
      if (projBitSet.get(i)) {
        projIndxLst.add(i);
      }
    }

    return projIndxLst;
  }

  /**
   * Push any equi join conditions that are not column references as Projections
   * on top of the children.
   *
   * @param factory
   *          Project factory to use.
   * @param inputRels
   *          inputs to a join
   * @param leftJoinKeys
   *          expressions for LHS of join key
   * @param rightJoinKeys
   *          expressions for RHS of join key
   * @param systemColCount
   *          number of system columns, usually zero. These columns are
   *          projected at the leading edge of the output row.
   * @param leftKeys
   *          on return this contains the join key positions from the new
   *          project rel on the LHS.
   * @param rightKeys
   *          on return this contains the join key positions from the new
   *          project rel on the RHS.
   * @return the join condition after the equi expressions pushed down.
   */
  public static RexNode projectNonColumnEquiConditions(ProjectFactory factory, RelNode[] inputRels,
      List<RexNode> leftJoinKeys, List<RexNode> rightJoinKeys, int systemColCount,
      List<Integer> leftKeys, List<Integer> rightKeys) {
    RelNode leftRel = inputRels[0];
    RelNode rightRel = inputRels[1];
    RexBuilder rexBuilder = leftRel.getCluster().getRexBuilder();
    RexNode outJoinCond = null;

    int origLeftInputSize = leftRel.getRowType().getFieldCount();
    int origRightInputSize = rightRel.getRowType().getFieldCount();

    List<RexNode> newLeftFields = new ArrayList<RexNode>();
    List<String> newLeftFieldNames = new ArrayList<String>();

    List<RexNode> newRightFields = new ArrayList<RexNode>();
    List<String> newRightFieldNames = new ArrayList<String>();
    int leftKeyCount = leftJoinKeys.size();
    int i;

    for (i = 0; i < origLeftInputSize; i++) {
      final RelDataTypeField field = leftRel.getRowType().getFieldList().get(i);
      newLeftFields.add(rexBuilder.makeInputRef(field.getType(), i));
      newLeftFieldNames.add(field.getName());
    }

    for (i = 0; i < origRightInputSize; i++) {
      final RelDataTypeField field = rightRel.getRowType().getFieldList().get(i);
      newRightFields.add(rexBuilder.makeInputRef(field.getType(), i));
      newRightFieldNames.add(field.getName());
    }

    int newKeyCount = 0;
    List<Pair<Integer, Integer>> origColEqConds = new ArrayList<Pair<Integer, Integer>>();
    for (i = 0; i < leftKeyCount; i++) {
      RexNode leftKey = leftJoinKeys.get(i);
      RexNode rightKey = rightJoinKeys.get(i);

      if (leftKey instanceof RexInputRef && rightKey instanceof RexInputRef) {
        origColEqConds.add(Pair.of(((RexInputRef) leftKey).getIndex(),
            ((RexInputRef) rightKey).getIndex()));
      } else {
        newLeftFields.add(leftKey);
        newLeftFieldNames.add(null);
        newRightFields.add(rightKey);
        newRightFieldNames.add(null);
        newKeyCount++;
      }
    }

    for (i = 0; i < origColEqConds.size(); i++) {
      Pair<Integer, Integer> p = origColEqConds.get(i);
      RexNode leftKey = leftJoinKeys.get(i);
      RexNode rightKey = rightJoinKeys.get(i);
      leftKeys.add(p.left);
      rightKeys.add(p.right);
      RexNode cond = rexBuilder.makeCall(
          SqlStdOperatorTable.EQUALS,
          rexBuilder.makeInputRef(leftKey.getType(), systemColCount + p.left),
          rexBuilder.makeInputRef(rightKey.getType(), systemColCount + origLeftInputSize
              + newKeyCount + p.right));
      if (outJoinCond == null) {
        outJoinCond = cond;
      } else {
        outJoinCond = rexBuilder.makeCall(SqlStdOperatorTable.AND, outJoinCond, cond);
      }
    }

    if (newKeyCount == 0) {
      return outJoinCond;
    }

    int newLeftOffset = systemColCount + origLeftInputSize;
    int newRightOffset = systemColCount + origLeftInputSize + origRightInputSize + newKeyCount;
    for (i = 0; i < newKeyCount; i++) {
      leftKeys.add(origLeftInputSize + i);
      rightKeys.add(origRightInputSize + i);
      RexNode cond = rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
          rexBuilder.makeInputRef(newLeftFields.get(i).getType(), newLeftOffset + i),
          rexBuilder.makeInputRef(newLeftFields.get(i).getType(), newRightOffset + i));
      if (outJoinCond == null) {
        outJoinCond = cond;
      } else {
        outJoinCond = rexBuilder.makeCall(SqlStdOperatorTable.AND, outJoinCond, cond);
      }
    }

    // added project if need to produce new keys than the original input
    // fields
    if (newKeyCount > 0) {
      leftRel = factory.createProject(leftRel, newLeftFields,
          SqlValidatorUtil.uniquify(newLeftFieldNames));
      rightRel = factory.createProject(rightRel, newRightFields,
          SqlValidatorUtil.uniquify(newRightFieldNames));
    }

    inputRels[0] = leftRel;
    inputRels[1] = rightRel;

    return outJoinCond;
  }

  /**
   * JoinPredicateInfo represents Join condition; JoinPredicate Info uses
   * JoinLeafPredicateInfo to represent individual conjunctive elements in the
   * predicate.<br>
   * JoinPredicateInfo = JoinLeafPredicateInfo1 and JoinLeafPredicateInfo2...<br>
   * <p>
   * JoinPredicateInfo:<br>
   * 1. preserves the order of conjuctive elements for
   * equi-join(equiJoinPredicateElements)<br>
   * 2. Stores set of projection indexes from left and right child which is part
   * of equi join keys; the indexes are both in child and Join node schema.<br>
   * 3. Keeps a map of projection indexes that are part of join keys to list of
   * conjuctive elements(JoinLeafPredicateInfo) that uses them.
   *
   */
  public static class JoinPredicateInfo {
    private final ImmutableList<JoinLeafPredicateInfo>                        nonEquiJoinPredicateElements;
    private final ImmutableList<JoinLeafPredicateInfo>                        equiJoinPredicateElements;
    private final ImmutableList<Set<Integer>>                                 projsJoinKeysInChildSchema;
    private final ImmutableList<Set<Integer>>                                 projsJoinKeysInJoinSchema;
    private final ImmutableMap<Integer, ImmutableList<JoinLeafPredicateInfo>> mapOfProjIndxInJoinSchemaToLeafPInfo;

    public JoinPredicateInfo(List<JoinLeafPredicateInfo> nonEquiJoinPredicateElements,
        List<JoinLeafPredicateInfo> equiJoinPredicateElements,
        List<Set<Integer>> projsJoinKeysInChildSchema,
        List<Set<Integer>> projsJoinKeysInJoinSchema,
        Map<Integer, ImmutableList<JoinLeafPredicateInfo>> mapOfProjIndxInJoinSchemaToLeafPInfo) {
      this.nonEquiJoinPredicateElements = ImmutableList.copyOf(nonEquiJoinPredicateElements);
      this.equiJoinPredicateElements = ImmutableList.copyOf(equiJoinPredicateElements);
      this.projsJoinKeysInChildSchema = ImmutableList
          .copyOf(projsJoinKeysInChildSchema);
      this.projsJoinKeysInJoinSchema = ImmutableList
          .copyOf(projsJoinKeysInJoinSchema);
      this.mapOfProjIndxInJoinSchemaToLeafPInfo = ImmutableMap
          .copyOf(mapOfProjIndxInJoinSchemaToLeafPInfo);
    }

    public List<JoinLeafPredicateInfo> getNonEquiJoinPredicateElements() {
      return this.nonEquiJoinPredicateElements;
    }

    public List<JoinLeafPredicateInfo> getEquiJoinPredicateElements() {
      return this.equiJoinPredicateElements;
    }

    public Set<Integer> getProjsFromLeftPartOfJoinKeysInChildSchema() {
      assert projsJoinKeysInChildSchema.size() == 2;
      return this.projsJoinKeysInChildSchema.get(0);
    }

    public Set<Integer> getProjsFromRightPartOfJoinKeysInChildSchema() {
      assert projsJoinKeysInChildSchema.size() == 2;
      return this.projsJoinKeysInChildSchema.get(1);
    }

    public Set<Integer> getProjsJoinKeysInChildSchema(int i) {
      return this.projsJoinKeysInChildSchema.get(i);
    }

    /**
     * NOTE: Join Schema = left Schema + (right Schema offset by
     * left.fieldcount). Hence its ok to return projections from left in child
     * schema.
     */
    public Set<Integer> getProjsFromLeftPartOfJoinKeysInJoinSchema() {
      assert projsJoinKeysInJoinSchema.size() == 2;
      return this.projsJoinKeysInJoinSchema.get(0);
    }

    public Set<Integer> getProjsFromRightPartOfJoinKeysInJoinSchema() {
      assert projsJoinKeysInJoinSchema.size() == 2;
      return this.projsJoinKeysInJoinSchema.get(1);
    }

    public Set<Integer> getProjsJoinKeysInJoinSchema(int i) {
      return this.projsJoinKeysInJoinSchema.get(i);
    }

    public Map<Integer, ImmutableList<JoinLeafPredicateInfo>> getMapOfProjIndxToLeafPInfo() {
      return this.mapOfProjIndxInJoinSchemaToLeafPInfo;
    }

    public static JoinPredicateInfo constructJoinPredicateInfo(Join j) throws CalciteSemanticException {
      return constructJoinPredicateInfo(j, j.getCondition());
    }

    public static JoinPredicateInfo constructJoinPredicateInfo(HiveMultiJoin mj) throws CalciteSemanticException {
      return constructJoinPredicateInfo(mj, mj.getCondition());
    }

    public static JoinPredicateInfo constructJoinPredicateInfo(Join j, RexNode predicate) throws CalciteSemanticException {
      return constructJoinPredicateInfo(j.getInputs(), j.getSystemFieldList(), predicate);
    }

    public static JoinPredicateInfo constructJoinPredicateInfo(HiveMultiJoin mj, RexNode predicate) throws CalciteSemanticException {
      final List<RelDataTypeField> systemFieldList = ImmutableList.of();
      return constructJoinPredicateInfo(mj.getInputs(), systemFieldList, predicate);
    }

    public static JoinPredicateInfo constructJoinPredicateInfo(List<RelNode> inputs,
            List<RelDataTypeField> systemFieldList, RexNode predicate) throws CalciteSemanticException {
      JoinPredicateInfo jpi = null;
      JoinLeafPredicateInfo jlpi = null;
      List<JoinLeafPredicateInfo> equiLPIList = new ArrayList<JoinLeafPredicateInfo>();
      List<JoinLeafPredicateInfo> nonEquiLPIList = new ArrayList<JoinLeafPredicateInfo>();
      List<Set<Integer>> projsJoinKeys = new ArrayList<Set<Integer>>();
      for (int i=0; i<inputs.size(); i++) {
        Set<Integer> projsJoinKeysInput = Sets.newHashSet();
        projsJoinKeys.add(projsJoinKeysInput);
      }
      List<Set<Integer>> projsJoinKeysInJoinSchema = new ArrayList<Set<Integer>>();
      for (int i=0; i<inputs.size(); i++) {
        Set<Integer> projsJoinKeysInJoinSchemaInput = Sets.newHashSet();
        projsJoinKeysInJoinSchema.add(projsJoinKeysInJoinSchemaInput);
      }
      Map<Integer, List<JoinLeafPredicateInfo>> tmpMapOfProjIndxInJoinSchemaToLeafPInfo = new HashMap<Integer, List<JoinLeafPredicateInfo>>();
      Map<Integer, ImmutableList<JoinLeafPredicateInfo>> mapOfProjIndxInJoinSchemaToLeafPInfo = new HashMap<Integer, ImmutableList<JoinLeafPredicateInfo>>();
      List<JoinLeafPredicateInfo> tmpJLPILst = null;
      List<RexNode> conjuctiveElements;

      // 1. Decompose Join condition to a number of leaf predicates
      // (conjuctive elements)
      conjuctiveElements = RelOptUtil.conjunctions(predicate);

      // 2. Walk through leaf predicates building up JoinLeafPredicateInfo
      for (RexNode ce : conjuctiveElements) {
        // 2.1 Construct JoinLeafPredicateInfo
        jlpi = JoinLeafPredicateInfo.constructJoinLeafPredicateInfo(inputs, systemFieldList, ce);

        // 2.2 Classify leaf predicate as Equi vs Non Equi
        if (jlpi.comparisonType.equals(SqlKind.EQUALS)) {
          equiLPIList.add(jlpi);

          // 2.2.1 Maintain join keys (in child & Join Schema)
          // 2.2.2 Update Join Key to JoinLeafPredicateInfo map with keys
          for (int i=0; i<inputs.size(); i++) {
            projsJoinKeys.get(i).addAll(jlpi.getProjsJoinKeysInChildSchema(i));
            projsJoinKeysInJoinSchema.get(i).addAll(jlpi.getProjsJoinKeysInJoinSchema(i));

            for (Integer projIndx : jlpi.getProjsJoinKeysInJoinSchema(i)) {
              tmpJLPILst = tmpMapOfProjIndxInJoinSchemaToLeafPInfo.get(projIndx);
              if (tmpJLPILst == null) {
                tmpJLPILst = new ArrayList<JoinLeafPredicateInfo>();
              }
              tmpJLPILst.add(jlpi);
              tmpMapOfProjIndxInJoinSchemaToLeafPInfo.put(projIndx, tmpJLPILst);
            }
          }
        } else {
          nonEquiLPIList.add(jlpi);
        }
      }

      // 3. Update Update Join Key to List<JoinLeafPredicateInfo> to use
      // ImmutableList
      for (Entry<Integer, List<JoinLeafPredicateInfo>> e : tmpMapOfProjIndxInJoinSchemaToLeafPInfo
          .entrySet()) {
        mapOfProjIndxInJoinSchemaToLeafPInfo.put(e.getKey(), ImmutableList.copyOf(e.getValue()));
      }

      // 4. Construct JoinPredicateInfo
      jpi = new JoinPredicateInfo(nonEquiLPIList, equiLPIList, projsJoinKeys,
          projsJoinKeysInJoinSchema, mapOfProjIndxInJoinSchemaToLeafPInfo);
      return jpi;
    }
  }

  /**
   * JoinLeafPredicateInfo represents leaf predicate in Join condition
   * (conjuctive lement).<br>
   * <p>
   * JoinLeafPredicateInfo:<br>
   * 1. Stores list of expressions from left and right child which is part of
   * equi join keys.<br>
   * 2. Stores set of projection indexes from left and right child which is part
   * of equi join keys; the indexes are both in child and Join node schema.<br>
   */
  public static class JoinLeafPredicateInfo {
    private final SqlKind                               comparisonType;
    private final ImmutableList<ImmutableList<RexNode>> joinKeyExprs;
    private final ImmutableList<ImmutableSet<Integer>>  projsJoinKeysInChildSchema;
    private final ImmutableList<ImmutableSet<Integer>>  projsJoinKeysInJoinSchema;

    public JoinLeafPredicateInfo(
            SqlKind comparisonType,
            List<List<RexNode>> joinKeyExprs,
            List<Set<Integer>> projsJoinKeysInChildSchema,
            List<Set<Integer>> projsJoinKeysInJoinSchema) {
      this.comparisonType = comparisonType;
      ImmutableList.Builder<ImmutableList<RexNode>> joinKeyExprsBuilder =
              ImmutableList.builder();
      for (int i=0; i<joinKeyExprs.size(); i++) {
        joinKeyExprsBuilder.add(ImmutableList.copyOf(joinKeyExprs.get(i)));
      }
      this.joinKeyExprs = joinKeyExprsBuilder.build();
      ImmutableList.Builder<ImmutableSet<Integer>> projsJoinKeysInChildSchemaBuilder =
              ImmutableList.builder();
      for (int i=0; i<projsJoinKeysInChildSchema.size(); i++) {
        projsJoinKeysInChildSchemaBuilder.add(
                ImmutableSet.copyOf(projsJoinKeysInChildSchema.get(i)));
      }
      this.projsJoinKeysInChildSchema = projsJoinKeysInChildSchemaBuilder.build();
      ImmutableList.Builder<ImmutableSet<Integer>> projsJoinKeysInJoinSchemaBuilder =
              ImmutableList.builder();
      for (int i=0; i<projsJoinKeysInJoinSchema.size(); i++) {
        projsJoinKeysInJoinSchemaBuilder.add(
                ImmutableSet.copyOf(projsJoinKeysInJoinSchema.get(i)));
      }
      this.projsJoinKeysInJoinSchema = projsJoinKeysInJoinSchemaBuilder.build();
    }

    public List<RexNode> getJoinExprs(int input) {
      return this.joinKeyExprs.get(input);
    }

    public Set<Integer> getProjsFromLeftPartOfJoinKeysInChildSchema() {
      assert projsJoinKeysInChildSchema.size() == 2;
      return this.projsJoinKeysInChildSchema.get(0);
    }

    public Set<Integer> getProjsFromRightPartOfJoinKeysInChildSchema() {
      assert projsJoinKeysInChildSchema.size() == 2;
      return this.projsJoinKeysInChildSchema.get(1);
    }

    public Set<Integer> getProjsJoinKeysInChildSchema(int input) {
      return this.projsJoinKeysInChildSchema.get(input);
    }

    public Set<Integer> getProjsFromLeftPartOfJoinKeysInJoinSchema() {
      assert projsJoinKeysInJoinSchema.size() == 2;
      return this.projsJoinKeysInJoinSchema.get(0);
    }

    public Set<Integer> getProjsFromRightPartOfJoinKeysInJoinSchema() {
      assert projsJoinKeysInJoinSchema.size() == 2;
      return this.projsJoinKeysInJoinSchema.get(1);
    }

    public Set<Integer> getProjsJoinKeysInJoinSchema(int input) {
      return this.projsJoinKeysInJoinSchema.get(input);
    }

    // We create the join predicate info object. The object contains the join condition,
    // split accordingly. If the join condition is not part of the equi-join predicate,
    // the returned object will be typed as SQLKind.OTHER.
    private static JoinLeafPredicateInfo constructJoinLeafPredicateInfo(List<RelNode> inputs,
            List<RelDataTypeField> systemFieldList, RexNode pe) throws CalciteSemanticException {
      JoinLeafPredicateInfo jlpi = null;
      List<Integer> filterNulls = new ArrayList<Integer>();
      List<List<RexNode>> joinExprs = new ArrayList<List<RexNode>>();
      for (int i=0; i<inputs.size(); i++) {
        joinExprs.add(new ArrayList<RexNode>());
      }

      // 1. Split leaf join predicate to expressions from left, right
      RexNode otherConditions = HiveRelOptUtil.splitHiveJoinCondition(systemFieldList, inputs, pe,
          joinExprs, filterNulls, null);

      if (otherConditions.isAlwaysTrue()) {
        // 2. Collect child projection indexes used
        List<Set<Integer>> projsJoinKeysInChildSchema =
                new ArrayList<Set<Integer>>();
        for (int i=0; i<inputs.size(); i++) {
          ImmutableSet.Builder<Integer> projsFromInputJoinKeysInChildSchema = ImmutableSet.builder();
          InputReferencedVisitor irvLeft = new InputReferencedVisitor();
          irvLeft.apply(joinExprs.get(i));
          projsFromInputJoinKeysInChildSchema.addAll(irvLeft.inputPosReferenced);
          projsJoinKeysInChildSchema.add(projsFromInputJoinKeysInChildSchema.build());
        }

        // 3. Translate projection indexes to join schema, by adding offset.
        List<Set<Integer>> projsJoinKeysInJoinSchema =
                new ArrayList<Set<Integer>>();
        // The offset of the first input does not need to change.
        projsJoinKeysInJoinSchema.add(projsJoinKeysInChildSchema.get(0));
        for (int i=1; i<inputs.size(); i++) {
          int offSet = inputs.get(i-1).getRowType().getFieldCount();
          ImmutableSet.Builder<Integer> projsFromInputJoinKeysInJoinSchema = ImmutableSet.builder();
          for (Integer indx : projsJoinKeysInChildSchema.get(i)) {
            projsFromInputJoinKeysInJoinSchema.add(indx + offSet);
          }
          projsJoinKeysInJoinSchema.add(projsFromInputJoinKeysInJoinSchema.build());
        }

        // 4. Construct JoinLeafPredicateInfo
        jlpi = new JoinLeafPredicateInfo(pe.getKind(), joinExprs,
            projsJoinKeysInChildSchema, projsJoinKeysInJoinSchema);
      } else {
        // 2. Construct JoinLeafPredicateInfo
        ImmutableBitSet refCols = InputFinder.bits(pe);
        int count = 0;
        for (int i=0; i<inputs.size(); i++) {
          final int length = inputs.get(i).getRowType().getFieldCount();
          ImmutableBitSet inputRange = ImmutableBitSet.range(count, count + length);
          if (inputRange.contains(refCols)) {
            joinExprs.get(i).add(pe);
          }
          count += length;
        }
        jlpi = new JoinLeafPredicateInfo(SqlKind.OTHER, joinExprs,
            new ArrayList<Set<Integer>>(), new ArrayList<Set<Integer>>());
      }

      return jlpi;
    }
  }

  public static boolean limitRelNode(RelNode rel) {
    if ((rel instanceof Sort) && ((Sort) rel).getCollation().getFieldCollations().isEmpty())
      return true;

    return false;
  }

  public static boolean orderRelNode(RelNode rel) {
    if ((rel instanceof Sort) && !((Sort) rel).getCollation().getFieldCollations().isEmpty())
      return true;

    return false;
  }

  /**
   * Get top level select starting from root. Assumption here is root can only
   * be Sort & Project. Also the top project should be at most 2 levels below
   * Sort; i.e Sort(Limit)-Sort(OB)-Select
   *
   * @param rootRel
   * @return
   */
  public static Pair<RelNode, RelNode> getTopLevelSelect(final RelNode rootRel) {
    RelNode tmpRel = rootRel;
    RelNode parentOforiginalProjRel = rootRel;
    HiveProject originalProjRel = null;

    while (tmpRel != null) {
      if (tmpRel instanceof HiveProject) {
        originalProjRel = (HiveProject) tmpRel;
        break;
      }
      parentOforiginalProjRel = tmpRel;
      tmpRel = tmpRel.getInput(0);
    }

    return (new Pair<RelNode, RelNode>(parentOforiginalProjRel, originalProjRel));
  }

  public static boolean isDeterministic(RexNode expr) {
    boolean deterministic = true;

    RexVisitor<Void> visitor = new RexVisitorImpl<Void>(true) {
      @Override
      public Void visitCall(org.apache.calcite.rex.RexCall call) {
        if (!call.getOperator().isDeterministic()) {
          throw new Util.FoundOne(call);
        }
        return super.visitCall(call);
      }
    };

    try {
      expr.accept(visitor);
    } catch (Util.FoundOne e) {
        deterministic = false;
    }

    return deterministic;
  }

  public static <T> ImmutableMap<Integer, T> getColInfoMap(List<T> hiveCols,
      int startIndx) {
    Builder<Integer, T> bldr = ImmutableMap.<Integer, T> builder();

    int indx = startIndx;
    for (T ci : hiveCols) {
      bldr.put(indx, ci);
      indx++;
    }

    return bldr.build();
  }

  public static ImmutableSet<Integer> shiftVColsSet(Set<Integer> hiveVCols, int shift) {
    ImmutableSet.Builder<Integer> bldr = ImmutableSet.<Integer> builder();

    for (Integer pos : hiveVCols) {
      bldr.add(shift + pos);
    }

    return bldr.build();
  }

  public static ImmutableMap<Integer, VirtualColumn> getVColsMap(List<VirtualColumn> hiveVCols,
      int startIndx) {
    Builder<Integer, VirtualColumn> bldr = ImmutableMap.<Integer, VirtualColumn> builder();

    int indx = startIndx;
    for (VirtualColumn vc : hiveVCols) {
      bldr.put(indx, vc);
      indx++;
    }

    return bldr.build();
  }

  public static ImmutableMap<String, Integer> getColNameIndxMap(List<FieldSchema> tableFields) {
    Builder<String, Integer> bldr = ImmutableMap.<String, Integer> builder();

    int indx = 0;
    for (FieldSchema fs : tableFields) {
      bldr.put(fs.getName(), indx);
      indx++;
    }

    return bldr.build();
  }

  public static ImmutableMap<String, Integer> getRowColNameIndxMap(List<RelDataTypeField> rowFields) {
    Builder<String, Integer> bldr = ImmutableMap.<String, Integer> builder();

    int indx = 0;
    for (RelDataTypeField rdt : rowFields) {
      bldr.put(rdt.getName(), indx);
      indx++;
    }

    return bldr.build();
  }

  public static ImmutableList<RexNode> getInputRef(List<Integer> inputRefs, RelNode inputRel) {
    ImmutableList.Builder<RexNode> bldr = ImmutableList.<RexNode> builder();
    for (int i : inputRefs) {
      bldr.add(new RexInputRef(i, inputRel.getRowType().getFieldList().get(i).getType()));
    }
    return bldr.build();
  }

  public static ExprNodeDesc getExprNode(Integer inputRefIndx, RelNode inputRel,
      ExprNodeConverter exprConv) {
    ExprNodeDesc exprNode = null;
    RexNode rexInputRef = new RexInputRef(inputRefIndx, inputRel.getRowType()
        .getFieldList().get(inputRefIndx).getType());
    exprNode = rexInputRef.accept(exprConv);

    return exprNode;
  }

  public static List<ExprNodeDesc> getExprNodes(List<Integer> inputRefs, RelNode inputRel,
      String inputTabAlias) {
    List<ExprNodeDesc> exprNodes = new ArrayList<ExprNodeDesc>();
    List<RexNode> rexInputRefs = getInputRef(inputRefs, inputRel);
    List<RexNode> exprs = inputRel.getChildExps();
    // TODO: Change ExprNodeConverter to be independent of Partition Expr
    ExprNodeConverter exprConv = new ExprNodeConverter(inputTabAlias, inputRel.getRowType(),
        new HashSet<Integer>(), inputRel.getCluster().getTypeFactory());
    for (int index = 0; index < rexInputRefs.size(); index++) {
      if (exprs.get(index) instanceof RexLiteral) {
        ExprNodeDesc exprNodeDesc = exprConv.visitLiteral((RexLiteral) exprs.get(index));
        exprNodes.add(exprNodeDesc);
      } else {
        RexNode iRef = rexInputRefs.get(index);
        exprNodes.add(iRef.accept(exprConv));
      }
    }
    return exprNodes;
  }

  public static List<String> getFieldNames(List<Integer> inputRefs, RelNode inputRel) {
    List<String> fieldNames = new ArrayList<String>();
    List<String> schemaNames = inputRel.getRowType().getFieldNames();
    for (Integer iRef : inputRefs) {
      fieldNames.add(schemaNames.get(iRef));
    }

    return fieldNames;
  }

  /**
   * Walks over an expression and determines whether it is constant.
   */
  public static class ConstantFinder implements RexVisitor<Boolean> {

    @Override
    public Boolean visitLiteral(RexLiteral literal) {
      return true;
    }

    @Override
    public Boolean visitInputRef(RexInputRef inputRef) {
      return false;
    }

    @Override
    public Boolean visitLocalRef(RexLocalRef localRef) {
      throw new RuntimeException("Not expected to be called.");
    }

    @Override
    public Boolean visitOver(RexOver over) {
      return false;
    }

    @Override
    public Boolean visitCorrelVariable(RexCorrelVariable correlVariable) {
      return false;
    }

    @Override
    public Boolean visitDynamicParam(RexDynamicParam dynamicParam) {
      return false;
    }

    @Override
    public Boolean visitCall(RexCall call) {
      // Constant if operator is deterministic and all operands are
      // constant.
      return call.getOperator().isDeterministic()
          && RexVisitorImpl.visitArrayAnd(this, call.getOperands());
    }

    @Override
    public Boolean visitRangeRef(RexRangeRef rangeRef) {
      return false;
    }

    @Override
    public Boolean visitFieldAccess(RexFieldAccess fieldAccess) {
      // "<expr>.FIELD" is constant iff "<expr>" is constant.
      return fieldAccess.getReferenceExpr().accept(this);
    }
  }

  public static Set<Integer> getInputRefs(RexNode expr) {
    InputRefsCollector irefColl = new InputRefsCollector(true);
    return irefColl.getInputRefSet();
  }

  private static class InputRefsCollector extends RexVisitorImpl<Void> {

    private final Set<Integer> inputRefSet = new HashSet<Integer>();

    private InputRefsCollector(boolean deep) {
      super(deep);
    }

    @Override
    public Void visitInputRef(RexInputRef inputRef) {
      inputRefSet.add(inputRef.getIndex());
      return null;
    }

    public Set<Integer> getInputRefSet() {
      return inputRefSet;
    }
  }
}
