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
package org.apache.hadoop.hive.ql.optimizer.calcite.rules;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.RelFactories.FilterFactory;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.hive.ql.optimizer.calcite.reloperators.HiveFilter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;


public class HivePreFilteringRule extends RelOptRule {

  protected static final Logger LOG = LoggerFactory
          .getLogger(HivePreFilteringRule.class.getName());


  public static final HivePreFilteringRule INSTANCE =
          new HivePreFilteringRule();

  private final FilterFactory filterFactory;


  private static final Set<SqlKind> COMPARISON = EnumSet.of(
          SqlKind.EQUALS,
          SqlKind.GREATER_THAN_OR_EQUAL,
          SqlKind.LESS_THAN_OR_EQUAL,
          SqlKind.GREATER_THAN,
          SqlKind.LESS_THAN,
          SqlKind.NOT_EQUALS);


  private HivePreFilteringRule() {
    super(operand(Filter.class,
            operand(RelNode.class, any())));
    this.filterFactory = HiveFilter.DEFAULT_FILTER_FACTORY;
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    final Filter filter = call.rel(0);
    final RelNode filterChild = call.rel(1);

    // If the filter is already on top of a TableScan,
    // we can bail out
    if (filterChild instanceof TableScan) {
      return false;
    }

    HiveRulesRegistry registry = call.getPlanner().
            getContext().unwrap(HiveRulesRegistry.class);

    // If this operator has been visited already by the rule,
    // we do not need to apply the optimization
    if (registry != null && registry.getVisited(this).contains(filter)) {
      return false;
    }

    return true;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final Filter filter = call.rel(0);

    // 0. Register that we have visited this operator in this rule
    HiveRulesRegistry registry = call.getPlanner().
            getContext().unwrap(HiveRulesRegistry.class);
    if (registry != null) {
      registry.registerVisited(this, filter);
    }

    final RexBuilder rexBuilder = filter.getCluster().getRexBuilder();

    final RexNode condition = RexUtil.pullFactors(rexBuilder, filter.getCondition());

    // 1. We extract possible candidates to be pushed down
    List<RexNode> commonOperands = new ArrayList<>();
    switch (condition.getKind()) {
      case AND:
        ImmutableList<RexNode> operands = RexUtil.flattenAnd(((RexCall) condition).getOperands());
        for (RexNode operand: operands) {
          if (operand.getKind() == SqlKind.OR) {
            commonOperands.addAll(extractCommonOperands(rexBuilder,operand));
          }
        }
        break;
      case OR:
        commonOperands = extractCommonOperands(rexBuilder,condition);
        break;
      default:
        return;
    }

    // 2. If we did not generate anything for the new predicate, we bail out
    if (commonOperands.isEmpty()) {
      return;
    }

    // 3. If the new conjuncts are already present in the plan, we bail out
    final RelOptPredicateList predicates = RelMetadataQuery.getPulledUpPredicates(filter.getInput());
    final List<RexNode> newConjuncts = new ArrayList<>();
    for (RexNode commonOperand : commonOperands) {
      boolean found = false;
      for (RexNode conjunct : predicates.pulledUpPredicates) {
        if (commonOperand.toString().equals(conjunct.toString())) {
          found = true;
          break;
        }
      }
      if (!found) {
        newConjuncts.add(commonOperand);
      }
    }
    if (newConjuncts.isEmpty()) {
      return;
    }

    // 4. Otherwise, we create a new condition
    final RexNode newCondition = RexUtil.pullFactors(rexBuilder,
            RexUtil.composeConjunction(rexBuilder, newConjuncts, false));

    // 5. We create the new filter that might be pushed down
    RelNode newFilter = filterFactory.createFilter(filter.getInput(), newCondition);
    RelNode newTopFilter = filterFactory.createFilter(newFilter, condition);

    // 6. We register both so we do not fire the rule on them again
    if (registry != null) {
      registry.registerVisited(this, newFilter);
      registry.registerVisited(this, newTopFilter);
    }

    call.transformTo(newTopFilter);

  }

  private static List<RexNode> extractCommonOperands(RexBuilder rexBuilder, RexNode condition) {
    assert condition.getKind() == SqlKind.OR;
    Multimap<String,RexNode> reductionCondition = LinkedHashMultimap.create();

    // Data structure to control whether a certain reference is present in every operand
    Set<String> refsInAllOperands = null;

    // 1. We extract the information necessary to create the predicate for the new
    //    filter; currently we support comparison functions, in and between
    ImmutableList<RexNode> operands = RexUtil.flattenOr(((RexCall) condition).getOperands());
    for (int i = 0; i < operands.size(); i++) {
      final RexNode operand = operands.get(i);

      final RexNode operandCNF = RexUtil.toCnf(rexBuilder, operand);
      final List<RexNode> conjunctions = RelOptUtil.conjunctions(operandCNF);

      Set<String> refsInCurrentOperand = Sets.newHashSet();
      for (RexNode conjunction: conjunctions) {
        // We do not know what it is, we bail out for safety
        if (!(conjunction instanceof RexCall)) {
          return new ArrayList<>();
        }
        RexCall conjCall = (RexCall) conjunction;
        RexNode ref = null;
        if(COMPARISON.contains(conjCall.getOperator().getKind())) {
          if (conjCall.operands.get(0) instanceof RexInputRef &&
                  conjCall.operands.get(1) instanceof RexLiteral) {
            ref = conjCall.operands.get(0);
          } else if (conjCall.operands.get(1) instanceof RexInputRef &&
                  conjCall.operands.get(0) instanceof RexLiteral) {
            ref = conjCall.operands.get(1);
          } else {
            // We do not know what it is, we bail out for safety
            return new ArrayList<>();
          }
        } else if(conjCall.getOperator().getKind().equals(SqlKind.IN)) {
          ref = conjCall.operands.get(0);
        } else if(conjCall.getOperator().getKind().equals(SqlKind.BETWEEN)) {
          ref = conjCall.operands.get(1);
        } else {
          // We do not know what it is, we bail out for safety
          return new ArrayList<>();
        }

        String stringRef = ref.toString();
        reductionCondition.put(stringRef, conjCall);
        refsInCurrentOperand.add(stringRef);
      }

      // Updates the references that are present in every operand up till now
      if (i == 0) {
        refsInAllOperands = refsInCurrentOperand;
      } else {
        refsInAllOperands = Sets.intersection(refsInAllOperands, refsInCurrentOperand);
      }
      // If we did not add any factor or there are no common factors, we can bail out
      if (refsInAllOperands.isEmpty()) {
        return new ArrayList<>();
      }
    }

    // 2. We gather the common factors and return them
    List<RexNode> commonOperands = new ArrayList<>();
    for (String ref : refsInAllOperands) {
      commonOperands.add(RexUtil.composeDisjunction(rexBuilder, reductionCondition.get(ref), false));
    }
    return commonOperands;
  }

}
