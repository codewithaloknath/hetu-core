/*
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
package io.prestosql.sql.analyzer;

import com.google.common.base.Joiner;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import io.hetu.core.spi.cube.CubeAggregateFunction;
import io.hetu.core.spi.cube.CubeMetadata;
import io.hetu.core.spi.cube.CubeStatus;
import io.hetu.core.spi.cube.io.CubeMetaStore;
import io.prestosql.Session;
import io.prestosql.SystemSessionProperties;
import io.prestosql.connector.DataCenterUtility;
import io.prestosql.cube.CubeManager;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.heuristicindex.HeuristicIndexerManager;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.OperatorNotFoundException;
import io.prestosql.metadata.TableMetadata;
import io.prestosql.security.AccessControl;
import io.prestosql.security.AllowAllAccessControl;
import io.prestosql.security.ViewAccessControl;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.PrestoWarning;
import io.prestosql.spi.StandardErrorCode;
import io.prestosql.spi.connector.CatalogName;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorViewDefinition;
import io.prestosql.spi.connector.ConnectorViewDefinition.ViewColumn;
import io.prestosql.spi.connector.CreateIndexMetadata;
import io.prestosql.spi.connector.QualifiedObjectName;
import io.prestosql.spi.function.FunctionKind;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.heuristicindex.IndexClient;
import io.prestosql.spi.heuristicindex.IndexRecord;
import io.prestosql.spi.heuristicindex.Pair;
import io.prestosql.spi.metadata.TableHandle;
import io.prestosql.spi.security.AccessDeniedException;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.security.ViewExpression;
import io.prestosql.spi.sql.expression.Types;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.CharType;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeNotFoundException;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.spi.type.VarcharType;
import io.prestosql.sql.ExpressionUtils;
import io.prestosql.sql.SqlPath;
import io.prestosql.sql.parser.ParsingException;
import io.prestosql.sql.parser.SqlParser;
import io.prestosql.sql.planner.ExpressionInterpreter;
import io.prestosql.sql.planner.SymbolsExtractor;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.tree.AddColumn;
import io.prestosql.sql.tree.AliasedRelation;
import io.prestosql.sql.tree.AllColumns;
import io.prestosql.sql.tree.Analyze;
import io.prestosql.sql.tree.AssignmentItem;
import io.prestosql.sql.tree.Call;
import io.prestosql.sql.tree.Comment;
import io.prestosql.sql.tree.Commit;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.CreateCube;
import io.prestosql.sql.tree.CreateIndex;
import io.prestosql.sql.tree.CreateSchema;
import io.prestosql.sql.tree.CreateTable;
import io.prestosql.sql.tree.CreateTableAsSelect;
import io.prestosql.sql.tree.CreateView;
import io.prestosql.sql.tree.Cube;
import io.prestosql.sql.tree.Deallocate;
import io.prestosql.sql.tree.DefaultTraversalVisitor;
import io.prestosql.sql.tree.Delete;
import io.prestosql.sql.tree.DereferenceExpression;
import io.prestosql.sql.tree.DropCache;
import io.prestosql.sql.tree.DropColumn;
import io.prestosql.sql.tree.DropCube;
import io.prestosql.sql.tree.DropIndex;
import io.prestosql.sql.tree.DropSchema;
import io.prestosql.sql.tree.DropTable;
import io.prestosql.sql.tree.DropView;
import io.prestosql.sql.tree.Except;
import io.prestosql.sql.tree.Execute;
import io.prestosql.sql.tree.Explain;
import io.prestosql.sql.tree.ExplainType;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.ExpressionRewriter;
import io.prestosql.sql.tree.ExpressionTreeRewriter;
import io.prestosql.sql.tree.FetchFirst;
import io.prestosql.sql.tree.FieldReference;
import io.prestosql.sql.tree.FrameBound;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.FunctionProperty;
import io.prestosql.sql.tree.Grant;
import io.prestosql.sql.tree.GroupBy;
import io.prestosql.sql.tree.GroupingElement;
import io.prestosql.sql.tree.GroupingOperation;
import io.prestosql.sql.tree.GroupingSets;
import io.prestosql.sql.tree.Identifier;
import io.prestosql.sql.tree.Insert;
import io.prestosql.sql.tree.InsertCube;
import io.prestosql.sql.tree.Intersect;
import io.prestosql.sql.tree.Join;
import io.prestosql.sql.tree.JoinCriteria;
import io.prestosql.sql.tree.JoinOn;
import io.prestosql.sql.tree.JoinUsing;
import io.prestosql.sql.tree.Lateral;
import io.prestosql.sql.tree.Limit;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.NaturalJoin;
import io.prestosql.sql.tree.Node;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.Offset;
import io.prestosql.sql.tree.OrderBy;
import io.prestosql.sql.tree.Prepare;
import io.prestosql.sql.tree.Property;
import io.prestosql.sql.tree.QualifiedName;
import io.prestosql.sql.tree.Query;
import io.prestosql.sql.tree.QuerySpecification;
import io.prestosql.sql.tree.Relation;
import io.prestosql.sql.tree.RenameColumn;
import io.prestosql.sql.tree.RenameSchema;
import io.prestosql.sql.tree.RenameTable;
import io.prestosql.sql.tree.ResetSession;
import io.prestosql.sql.tree.Revoke;
import io.prestosql.sql.tree.Rollback;
import io.prestosql.sql.tree.Rollup;
import io.prestosql.sql.tree.Row;
import io.prestosql.sql.tree.SampledRelation;
import io.prestosql.sql.tree.Select;
import io.prestosql.sql.tree.SelectItem;
import io.prestosql.sql.tree.SetOperation;
import io.prestosql.sql.tree.SetSession;
import io.prestosql.sql.tree.SimpleGroupBy;
import io.prestosql.sql.tree.SingleColumn;
import io.prestosql.sql.tree.SortItem;
import io.prestosql.sql.tree.StartTransaction;
import io.prestosql.sql.tree.Statement;
import io.prestosql.sql.tree.Table;
import io.prestosql.sql.tree.TableSubquery;
import io.prestosql.sql.tree.Unnest;
import io.prestosql.sql.tree.Update;
import io.prestosql.sql.tree.UpdateIndex;
import io.prestosql.sql.tree.Use;
import io.prestosql.sql.tree.VacuumTable;
import io.prestosql.sql.tree.Values;
import io.prestosql.sql.tree.Window;
import io.prestosql.sql.tree.WindowFrame;
import io.prestosql.sql.tree.With;
import io.prestosql.sql.tree.WithQuery;
import io.prestosql.sql.util.AstUtils;
import io.prestosql.type.TypeCoercion;
import io.prestosql.utils.HeuristicIndexUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.transform;
import static io.prestosql.SystemSessionProperties.getMaxGroupingSets;
import static io.prestosql.SystemSessionProperties.isEnableStarTreeIndex;
import static io.prestosql.cube.CubeManager.STAR_TREE;
import static io.prestosql.metadata.MetadataUtil.createQualifiedObjectName;
import static io.prestosql.spi.StandardErrorCode.INVALID_COLUMN_MASK;
import static io.prestosql.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.prestosql.spi.StandardErrorCode.INVALID_ROW_FILTER;
import static io.prestosql.spi.StandardErrorCode.NOT_FOUND;
import static io.prestosql.spi.connector.CreateIndexMetadata.Level.UNDEFINED;
import static io.prestosql.spi.connector.StandardWarningCode.CUBE_NOT_FOUND;
import static io.prestosql.spi.connector.StandardWarningCode.REDUNDANT_ORDER_BY;
import static io.prestosql.spi.function.FunctionKind.AGGREGATE;
import static io.prestosql.spi.function.FunctionKind.WINDOW;
import static io.prestosql.spi.heuristicindex.IndexRecord.INPROGRESS_PROPERTY_KEY;
import static io.prestosql.spi.sql.expression.Types.FrameBoundType.CURRENT_ROW;
import static io.prestosql.spi.sql.expression.Types.FrameBoundType.FOLLOWING;
import static io.prestosql.spi.sql.expression.Types.FrameBoundType.PRECEDING;
import static io.prestosql.spi.sql.expression.Types.FrameBoundType.UNBOUNDED_FOLLOWING;
import static io.prestosql.spi.sql.expression.Types.FrameBoundType.UNBOUNDED_PRECEDING;
import static io.prestosql.spi.sql.expression.Types.WindowFrameType.RANGE;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.UnknownType.UNKNOWN;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.sql.NodeUtils.getSortItemsFromOrderBy;
import static io.prestosql.sql.NodeUtils.mapFromProperties;
import static io.prestosql.sql.ParsingUtil.createParsingOptions;
import static io.prestosql.sql.analyzer.AggregationAnalyzer.verifyOrderByAggregations;
import static io.prestosql.sql.analyzer.AggregationAnalyzer.verifySourceAggregations;
import static io.prestosql.sql.analyzer.Analyzer.verifyNoAggregateWindowOrGroupingFunctions;
import static io.prestosql.sql.analyzer.ExpressionAnalyzer.createConstantAnalyzer;
import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractAggregateFunctions;
import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractExpressions;
import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractLocation;
import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractWindowFunctions;
import static io.prestosql.sql.analyzer.ScopeReferenceExtractor.hasReferencesToScope;
import static io.prestosql.sql.analyzer.SemanticErrorCode.AMBIGUOUS_ATTRIBUTE;
import static io.prestosql.sql.analyzer.SemanticErrorCode.COLUMN_NAME_NOT_SPECIFIED;
import static io.prestosql.sql.analyzer.SemanticErrorCode.COLUMN_TYPE_UNKNOWN;
import static io.prestosql.sql.analyzer.SemanticErrorCode.DUPLICATE_COLUMN_NAME;
import static io.prestosql.sql.analyzer.SemanticErrorCode.DUPLICATE_PROPERTY;
import static io.prestosql.sql.analyzer.SemanticErrorCode.DUPLICATE_RELATION;
import static io.prestosql.sql.analyzer.SemanticErrorCode.INDEX_ALREADY_EXISTS;
import static io.prestosql.sql.analyzer.SemanticErrorCode.INSERT_INTO_CUBE;
import static io.prestosql.sql.analyzer.SemanticErrorCode.INVALID_FETCH_FIRST_ROW_COUNT;
import static io.prestosql.sql.analyzer.SemanticErrorCode.INVALID_FUNCTION_NAME;
import static io.prestosql.sql.analyzer.SemanticErrorCode.INVALID_LIMIT_ROW_COUNT;
import static io.prestosql.sql.analyzer.SemanticErrorCode.INVALID_OFFSET_ROW_COUNT;
import static io.prestosql.sql.analyzer.SemanticErrorCode.INVALID_ORDINAL;
import static io.prestosql.sql.analyzer.SemanticErrorCode.INVALID_PROCEDURE_ARGUMENTS;
import static io.prestosql.sql.analyzer.SemanticErrorCode.INVALID_WINDOW_FRAME;
import static io.prestosql.sql.analyzer.SemanticErrorCode.MISMATCHED_COLUMN_ALIASES;
import static io.prestosql.sql.analyzer.SemanticErrorCode.MISMATCHED_SET_COLUMN_TYPES;
import static io.prestosql.sql.analyzer.SemanticErrorCode.MISSING_ATTRIBUTE;
import static io.prestosql.sql.analyzer.SemanticErrorCode.MISSING_CATALOG;
import static io.prestosql.sql.analyzer.SemanticErrorCode.MISSING_COLUMN;
import static io.prestosql.sql.analyzer.SemanticErrorCode.MISSING_CUBE;
import static io.prestosql.sql.analyzer.SemanticErrorCode.MISSING_INDEX;
import static io.prestosql.sql.analyzer.SemanticErrorCode.MISSING_ORDER_BY;
import static io.prestosql.sql.analyzer.SemanticErrorCode.MISSING_SCHEMA;
import static io.prestosql.sql.analyzer.SemanticErrorCode.MISSING_TABLE;
import static io.prestosql.sql.analyzer.SemanticErrorCode.MUST_BE_WINDOW_FUNCTION;
import static io.prestosql.sql.analyzer.SemanticErrorCode.NESTED_WINDOW;
import static io.prestosql.sql.analyzer.SemanticErrorCode.NONDETERMINISTIC_ORDER_BY_EXPRESSION_WITH_SELECT_DISTINCT;
import static io.prestosql.sql.analyzer.SemanticErrorCode.NON_NUMERIC_SAMPLE_PERCENTAGE;
import static io.prestosql.sql.analyzer.SemanticErrorCode.NOT_SUPPORTED;
import static io.prestosql.sql.analyzer.SemanticErrorCode.ORDER_BY_MUST_BE_IN_SELECT;
import static io.prestosql.sql.analyzer.SemanticErrorCode.TABLE_ALREADY_EXISTS;
import static io.prestosql.sql.analyzer.SemanticErrorCode.TABLE_STATE_INCORRECT;
import static io.prestosql.sql.analyzer.SemanticErrorCode.TOO_MANY_ARGUMENTS;
import static io.prestosql.sql.analyzer.SemanticErrorCode.TOO_MANY_GROUPING_SETS;
import static io.prestosql.sql.analyzer.SemanticErrorCode.TYPE_MISMATCH;
import static io.prestosql.sql.analyzer.SemanticErrorCode.VIEW_ANALYSIS_ERROR;
import static io.prestosql.sql.analyzer.SemanticErrorCode.VIEW_IS_RECURSIVE;
import static io.prestosql.sql.analyzer.SemanticErrorCode.VIEW_IS_STALE;
import static io.prestosql.sql.analyzer.SemanticErrorCode.VIEW_PARSE_ERROR;
import static io.prestosql.sql.analyzer.SemanticErrorCode.WILDCARD_WITHOUT_FROM;
import static io.prestosql.sql.planner.ExpressionDeterminismEvaluator.isDeterministic;
import static io.prestosql.sql.planner.ExpressionInterpreter.expressionOptimizer;
import static io.prestosql.sql.tree.ExplainType.Type.DISTRIBUTED;
import static io.prestosql.util.MoreLists.mappedCopy;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

class StatementAnalyzer
{
    private final Analysis analysis;
    private final Metadata metadata;
    private final TypeCoercion typeCoercion;
    private final Session session;
    private final SqlParser sqlParser;
    private final AccessControl accessControl;
    private final WarningCollector warningCollector;
    private HeuristicIndexerManager heuristicIndexerManager;
    private CubeManager cubeManager;

    public StatementAnalyzer(
            Analysis analysis,
            Metadata metadata,
            SqlParser sqlParser,
            AccessControl accessControl,
            Session session,
            WarningCollector warningCollector)
    {
        this.analysis = requireNonNull(analysis, "analysis is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.typeCoercion = new TypeCoercion(metadata::getType);
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.session = requireNonNull(session, "session is null");
        this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
    }

    public StatementAnalyzer(
            Analysis analysis,
            Metadata metadata,
            SqlParser sqlParser,
            AccessControl accessControl,
            Session session,
            WarningCollector warningCollector,
            HeuristicIndexerManager heuristicIndexerManager,
            CubeManager cubeManager)
    {
        this(analysis, metadata, sqlParser, accessControl, session, warningCollector);
        this.heuristicIndexerManager = requireNonNull(heuristicIndexerManager, "heuristicIndexerManager is null");
        this.cubeManager = requireNonNull(cubeManager, "cubeManager is null");
    }

    public Scope analyze(Node node, Scope outerQueryScope)
    {
        return analyze(node, Optional.of(outerQueryScope));
    }

    public Scope analyze(Node node, Optional<Scope> outerQueryScope)
    {
        return new Visitor(outerQueryScope, warningCollector, Optional.empty())
                .process(node, Optional.empty());
    }

    public Scope analyzeForUpdate(Table table, Optional<Scope> outerQueryScope, UpdateKind updateKind)
    {
        return new Visitor(outerQueryScope, warningCollector, Optional.of(updateKind))
                .process(table, Optional.empty());
    }

    private enum UpdateKind
    {
        DELETE,
        UPDATE;
    }

    /**
     * Visitor context represents local query scope (if exists). The invariant is
     * that the local query scopes hierarchy should always have outer query scope
     * (if provided) as ancestor.
     */
    private class Visitor
            extends DefaultTraversalVisitor<Scope, Optional<Scope>>
    {
        private final Optional<Scope> outerQueryScope;
        private final WarningCollector warningCollector;
        private final Optional<UpdateKind> updateKind;

        private Visitor(Optional<Scope> outerQueryScope, WarningCollector warningCollector, Optional<UpdateKind> updateKind)
        {
            this.outerQueryScope = requireNonNull(outerQueryScope, "outerQueryScope is null");
            this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
            this.updateKind = requireNonNull(updateKind, "updateKind is null");
        }

        @Override
        public Scope process(Node node, Optional<Scope> scope)
        {
            Scope returnScope = super.process(node, scope);
            checkState(returnScope.getOuterQueryParent().equals(outerQueryScope), "result scope should have outer query scope equal with parameter outer query scope");
            if (scope.isPresent()) {
                checkState(hasScopeAsLocalParent(returnScope, scope.get()), "return scope should have context scope as one of ancestors");
            }
            return returnScope;
        }

        private Scope process(Node node, Scope scope)
        {
            return process(node, Optional.of(scope));
        }

        @Override
        protected Scope visitUse(Use node, Optional<Scope> scope)
        {
            throw new SemanticException(NOT_SUPPORTED, node, "USE statement is not supported");
        }

        @Override
        protected Scope visitInsert(Insert insert, Optional<Scope> scope)
        {
            QualifiedObjectName targetTable = createQualifiedObjectName(session, insert, insert.getTarget());
            if (metadata.getView(session, targetTable).isPresent()) {
                throw new SemanticException(NOT_SUPPORTED, insert, "Inserting into views is not supported");
            }

            Optional<CubeMetaStore> optionalCubeMetaStore = cubeManager.getMetaStore(STAR_TREE);
            if (optionalCubeMetaStore.isPresent() && optionalCubeMetaStore.get().getMetadataFromCubeName(targetTable.toString()).isPresent()) {
                throw new SemanticException(INSERT_INTO_CUBE, insert, "%s is a star-tree cube, INSERT is not available", targetTable);
            }

            // analyze the query that creates the data
            Scope queryScope = process(insert.getQuery(), scope);

            if (insert.getOverwrite()) {
                // set the insert as insert overwrite
                analysis.setUpdateType("INSERT OVERWRITE", targetTable);
                analysis.setIsInsertOverwrite(true);
            }
            else {
                analysis.setUpdateType("INSERT", targetTable);
            }

            // verify the insert destination columns match the query
            Optional<TableHandle> targetTableHandle = metadata.getTableHandle(session, targetTable);
            if (!targetTableHandle.isPresent()) {
                throw new SemanticException(MISSING_TABLE, insert, "Table '%s' does not exist", targetTable);
            }
            accessControl.checkCanInsertIntoTable(session.getRequiredTransactionId(), session.getIdentity(), targetTable);

            TableMetadata tableMetadata = metadata.getTableMetadata(session, targetTableHandle.get());
            List<String> tableColumns = tableMetadata.getColumns().stream()
                    .filter(column -> !column.isHidden())
                    .map(ColumnMetadata::getName)
                    .collect(toImmutableList());

            List<String> insertColumns;
            if (insert.getColumns().isPresent()) {
                insertColumns = insert.getColumns().get().stream()
                        .map(Identifier::getValue)
                        .map(column -> column.toLowerCase(ENGLISH))
                        .collect(toImmutableList());

                Set<String> columnNames = new HashSet<>();
                for (String insertColumn : insertColumns) {
                    if (!tableColumns.contains(insertColumn)) {
                        throw new SemanticException(MISSING_COLUMN, insert, "Insert column name does not exist in target table: %s", insertColumn);
                    }
                    if (!columnNames.add(insertColumn)) {
                        throw new SemanticException(DUPLICATE_COLUMN_NAME, insert, "Insert column name is specified more than once: %s", insertColumn);
                    }
                }
            }
            else {
                insertColumns = tableColumns;
            }

            Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, targetTableHandle.get());
            analysis.setInsert(new Analysis.Insert(
                    targetTableHandle.get(),
                    insertColumns.stream().map(columnHandles::get).collect(toImmutableList())));

            Iterable<Type> tableTypes = insertColumns.stream()
                    .map(insertColumn -> tableMetadata.getColumn(insertColumn).getType())
                    .collect(toImmutableList());

            Iterable<Type> queryTypes = transform(queryScope.getRelationType().getVisibleFields(), Field::getType);

            if (!typesMatchForInsert(tableTypes, queryTypes)) {
                throw new SemanticException(MISMATCHED_SET_COLUMN_TYPES, insert, "Insert query has mismatched column types: " +
                        "Table: [" + Joiner.on(", ").join(tableTypes) + "], " +
                        "Query: [" + Joiner.on(", ").join(queryTypes) + "]");
            }

            return createAndAssignScope(insert, scope, Field.newUnqualified("rows", BIGINT));
        }

        private boolean typesMatchForInsert(Iterable<Type> tableTypes, Iterable<Type> queryTypes)
        {
            if (Iterables.size(tableTypes) != Iterables.size(queryTypes)) {
                return false;
            }

            Iterator<Type> tableTypesIterator = tableTypes.iterator();
            Iterator<Type> queryTypesIterator = queryTypes.iterator();
            while (tableTypesIterator.hasNext()) {
                Type tableType = tableTypesIterator.next();
                Type queryType = queryTypesIterator.next();
                if (hasNestedBoundedCharacterType(tableType)) {
                    if (!typeCoercion.canCoerce(queryType, tableType)) {
                        return false;
                    }
                }
                else if (!(typeCoercion.canCoerce(queryType, tableType)
                        || (SystemSessionProperties.isImplicitConversionEnabled(session)
                        && typeCoercion.canCoerceWithCast(queryType, tableType)))) {
                    return false;
                }
            }

            return true;
        }

        private boolean hasNestedBoundedCharacterType(Type type)
        {
            if (type instanceof ArrayType) {
                return hasBoundedCharacterType(((ArrayType) type).getElementType());
            }

            if (type instanceof MapType) {
                return hasBoundedCharacterType(((MapType) type).getKeyType()) || hasBoundedCharacterType(((MapType) type).getValueType());
            }

            if (type instanceof RowType) {
                for (Type fieldType : type.getTypeParameters()) {
                    if (hasBoundedCharacterType(fieldType)) {
                        return true;
                    }
                }
            }

            return false;
        }

        private boolean hasBoundedCharacterType(Type type)
        {
            return type instanceof CharType || (type instanceof VarcharType && !((VarcharType) type).isUnbounded()) || hasNestedBoundedCharacterType(type);
        }

        @Override
        protected Scope visitInsertCube(InsertCube insertCube, Optional<Scope> scope)
        {
            QualifiedObjectName targetCube = createQualifiedObjectName(session, insertCube, insertCube.getCubeName());
            CubeMetaStore cubeMetaStore = cubeManager.getMetaStore(STAR_TREE).orElseThrow(() -> new RuntimeException("Hetu metastore must be initialized"));
            CubeMetadata cubeMetadata = cubeMetaStore.getMetadataFromCubeName(targetCube.toString())
                    .orElseThrow(() -> new SemanticException(INSERT_INTO_CUBE, insertCube, "Cube '%s' is not found, INSERT INTO CUBE is not applicable.", targetCube));
            Optional<TableHandle> targetCubeHandle = metadata.getTableHandle(session, targetCube);
            if (!targetCubeHandle.isPresent()) {
                throw new SemanticException(MISSING_CUBE, insertCube, "Cube '%s' table handle does not exist", targetCube);
            }

            TableMetadata cubeTableMetadata = metadata.getTableMetadata(session, targetCubeHandle.get());
            boolean isPartitioned = cubeTableMetadata.getMetadata().getProperties().containsKey("partitioned_by");
            if (insertCube.isOverwrite() && isPartitioned) {
                throw new PrestoException(StandardErrorCode.NOT_SUPPORTED, "INSERT OVERWRITE not supported on partitioned cube. Drop and recreate cube, if needed.");
            }

            QualifiedObjectName tableName = QualifiedObjectName.valueOf(cubeMetadata.getSourceTableName());
            TableHandle sourceTableHandle = metadata.getTableHandle(session, tableName)
                    .orElseThrow(() -> new SemanticException(MISSING_TABLE, insertCube, "Source table '%s' on which cube was built is missing", tableName.toString()));

            //Cube status is determined based on the last modified timestamp of the source table
            //Without that Cube might return incorrect results if the table was updated but cube was not.
            LongSupplier tableLastModifiedTime = metadata.getTableLastModifiedTimeSupplier(session, sourceTableHandle);
            if (tableLastModifiedTime == null) {
                throw new SemanticException(TABLE_STATE_INCORRECT, insertCube, "Cannot allow insert into cube. Cube might return incorrect results. Unable to identify last modified of the time source table.");
            }
            // If Original table was updated since Cube was built then We cannot allow any more updates on the Cube.
            // User must create new cube from the source table and try insert overwrite cube
            if (!insertCube.isOverwrite() && cubeMetadata.getCubeStatus() == CubeStatus.READY && tableLastModifiedTime.getAsLong() > cubeMetadata.getSourceTableLastUpdatedTime()) {
                throw new SemanticException(TABLE_STATE_INCORRECT, insertCube, "Cannot insert into cube. Source table has been updated since Cube was last updated. Try INSERT OVERWRITE CUBE or Create new a cube");
            }

            Scope queryScope = process(insertCube.getQuery(), scope);
            accessControl.checkCanInsertIntoTable(session.getRequiredTransactionId(), session.getIdentity(), targetCube);
            if (insertCube.isOverwrite()) {
                // set the insert as insert overwrite
                analysis.setUpdateType("INSERT OVERWRITE CUBE", targetCube);
                analysis.setCubeOverwrite(true);
            }
            else {
                analysis.setUpdateType("INSERT CUBE", targetCube);
            }
            Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, targetCubeHandle.get());
            analysis.setCubeInsert(new Analysis.CubeInsert(
                    cubeMetadata,
                    targetCubeHandle.get(),
                    sourceTableHandle,
                    insertCube.getColumns().stream().map(Identifier::getValue).map(columnHandles::get).collect(Collectors.toList())));
            return createAndAssignScope(insertCube, scope, Field.newUnqualified("rows", BIGINT));
        }

        @Override
        protected Scope visitDelete(Delete node, Optional<Scope> scope)
        {
            Table table = node.getTable();
            QualifiedObjectName tableName = createQualifiedObjectName(session, table, table.getName());
            if (metadata.getView(session, tableName).isPresent()) {
                throw new SemanticException(NOT_SUPPORTED, node, "Deleting from views is not supported");
            }

            accessControl.checkCanDeleteFromTable(session.getRequiredTransactionId(), session.getIdentity(), tableName);

            Optional<CubeMetaStore> optionalCubeMetaStore = cubeManager.getMetaStore(STAR_TREE);
            if (optionalCubeMetaStore.isPresent() && optionalCubeMetaStore.get().getMetadataFromCubeName(tableName.toString()).isPresent()) {
                throw new SemanticException(NOT_SUPPORTED, node, "%s is a star-tree cube, DELETE is not supported", tableName);
            }

            // Analyzer checks for select permissions but DELETE has a separate permission, so disable access checks
            // TODO: we shouldn't need to create a new analyzer. The access control should be carried in the context object
            StatementAnalyzer analyzer = new StatementAnalyzer(
                    analysis,
                    metadata,
                    sqlParser,
                    new AllowAllAccessControl(),
                    session,
                    warningCollector);

            Scope tableScope = analyzer.analyzeForUpdate(table, scope, UpdateKind.DELETE);
            node.getWhere().ifPresent(where -> analyzeWhere(node, tableScope, where));

            analysis.setUpdateType("DELETE", tableName);

            return createAndAssignScope(node, scope, Field.newUnqualified("rows", BIGINT));
        }

        @Override
        public Scope visitUpdate(Update node, Optional<Scope> scope)
        {
            Table table = node.getTable();
            QualifiedObjectName tableName = createQualifiedObjectName(session, table, table.getName());
            if (metadata.getView(session, tableName).isPresent()) {
                throw new SemanticException(NOT_SUPPORTED, node, "Updating view is not supported");
            }

            // check access right
            accessControl.checkCanUpdateTable(session.getRequiredTransactionId(), session.getIdentity(), tableName);

            Optional<CubeMetaStore> optionalCubeMetaStore = cubeManager.getMetaStore(STAR_TREE);
            if (optionalCubeMetaStore.isPresent() && optionalCubeMetaStore.get().getMetadataFromCubeName(tableName.toString()).isPresent()) {
                throw new SemanticException(NOT_SUPPORTED, node, "%s is a star-tree cube, UPDATE is not supported", tableName);
            }

            // verify the existing of table
            Optional<TableHandle> targetTableHandle = metadata.getTableHandle(session, tableName);
            if (!targetTableHandle.isPresent()) {
                throw new SemanticException(MISSING_TABLE, node, "Table '%s' does not exist", tableName);
            }

            TableMetadata tableMetadata = metadata.getTableMetadata(session, targetTableHandle.get());
            Set<String> tableColumns = tableMetadata.getColumns().stream()
                    .filter(column -> !column.isHidden())
                    .map(ColumnMetadata::getName)
                    .collect(toImmutableSet());

            List<ColumnMetadata> tableColumnMeta = tableMetadata.getColumns().stream().collect(toImmutableList());
            Map<String, Type> tableColumnsTypeMap = tableColumnMeta.stream().collect(toImmutableMap(ColumnMetadata::getName, ColumnMetadata::getType));

            Set<String> assignmentTargets = node.getAssignmentItems().stream()
                    .map(assignment -> assignment.getName().toString())
                    .collect(toImmutableSet());

            List<ColumnMetadata> updatedColumns = tableColumnMeta.stream()
                    .filter(column -> assignmentTargets.contains(column.getName()))
                    .collect(toImmutableList());

            analysis.setUpdatedColumns(updatedColumns);

            // get immutableColumns from connector
            List<String> immutableColumns = new ArrayList<>();
            if (tableMetadata.getImmutableColumns().isPresent()) {
                immutableColumns = tableMetadata.getImmutableColumns().get().stream()
                        .map(ColumnMetadata::getName)
                        .collect(toImmutableList());
            }

            StatementAnalyzer analyzer = new StatementAnalyzer(
                    analysis,
                    metadata,
                    sqlParser,
                    new AllowAllAccessControl(),
                    session,
                    warningCollector);

            Scope tableScope = analyzer.analyzeForUpdate(table, scope, UpdateKind.UPDATE);
            // validate the columns and set values
            if (node.getAssignmentItems().size() > 0) {
                Set<String> updateColumnNames = new HashSet<>();
                for (AssignmentItem assignmentItem : node.getAssignmentItems()) {
                    String updateColumnName = assignmentItem.getName().toString();
                    if (!tableColumns.contains(updateColumnName)) {
                        throw new SemanticException(MISSING_COLUMN, node, "Update column name does not exist in target table: %s", updateColumnName);
                    }
                    if (!updateColumnNames.add(updateColumnName)) {
                        throw new SemanticException(DUPLICATE_COLUMN_NAME, node, "Update column name is specified more than once: %s", updateColumnName);
                    }
                    // check the column to be updated is updatable or not
                    if (immutableColumns.contains(updateColumnName)) {
                        throw new SemanticException(MISMATCHED_SET_COLUMN_TYPES, node, "Update of the column %s is not supported. ", updateColumnName);
                    }

                    Expression setValue = assignmentItem.getValue();
                    ExpressionAnalysis expressionAnalysis = analyzeExpression(setValue, tableScope);
                    Type tableColumnType = tableColumnsTypeMap.get(updateColumnName);
                    Type setValueType = expressionAnalysis.getExpressionTypes().get(NodeRef.of(setValue));
                    if (targetTableHandle.get().getConnectorHandle().isUpdateAsInsertSupported()
                            && !typeCoercion.canCoerce(setValueType, tableColumnType)) {
                        throw new SemanticException(MISMATCHED_SET_COLUMN_TYPES, node, "Update column value %s has mismatched column type: %s ", setValue, tableColumnType);
                    }
                    if (!tableColumnType.equals(setValueType)) {
                        analysis.addCoercion(setValue, tableColumnType, typeCoercion.isTypeOnlyCoercion(setValueType, tableColumnType));
                    }
                    analysis.recordSubqueries(node, expressionAnalysis);
                }
            }
            else {
                throw new SemanticException(MISSING_COLUMN, node, "Update column is missing");
            }
            Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, targetTableHandle.get());
            analysis.setUpdate(new Analysis.Update(
                    targetTableHandle.get(),
                    tableColumns.stream().map(columnHandles::get).collect(toImmutableList())));

            node.getWhere().ifPresent(where -> analyzeWhere(node, tableScope, where));

            analysis.setUpdateType("UPDATE", tableName);

            return createAndAssignScope(node, scope, Field.newUnqualified("rows", BIGINT));
        }

        @Override
        protected Scope visitAnalyze(Analyze node, Optional<Scope> scope)
        {
            QualifiedObjectName tableName = createQualifiedObjectName(session, node, node.getTableName());
            analysis.setUpdateType("ANALYZE", tableName);

            // verify the target table exists and it's not a view
            if (metadata.getView(session, tableName).isPresent()) {
                throw new SemanticException(NOT_SUPPORTED, node, "Analyzing views is not supported");
            }

            validateProperties(node.getProperties(), scope);
            CatalogName catalogName = metadata.getCatalogHandle(session, tableName.getCatalogName())
                    .orElseThrow(() -> new PrestoException(NOT_FOUND, "Catalog not found: " + tableName.getCatalogName()));

            Map<String, Object> analyzeProperties = metadata.getAnalyzePropertyManager().getProperties(
                    catalogName,
                    catalogName.getCatalogName(),
                    mapFromProperties(node.getProperties()),
                    session,
                    metadata,
                    analysis.getParameters());
            TableHandle tableHandle = metadata.getTableHandleForStatisticsCollection(session, tableName, analyzeProperties)
                    .orElseThrow(() -> (new SemanticException(MISSING_TABLE, node, "Table '%s' does not exist", tableName)));

            // user must have read and insert permission in order to analyze stats of a table
            analysis.addTableColumnReferences(
                    accessControl,
                    session.getIdentity(),
                    ImmutableMultimap.<QualifiedObjectName, String>builder()
                            .putAll(tableName, metadata.getColumnHandles(session, tableHandle).keySet())
                            .build());
            try {
                accessControl.checkCanInsertIntoTable(session.getRequiredTransactionId(), session.getIdentity(), tableName);
            }
            catch (AccessDeniedException exception) {
                throw new AccessDeniedException(format("Cannot ANALYZE (missing insert privilege) table %s", tableName));
            }

            analysis.setAnalyzeTarget(tableHandle);
            return createAndAssignScope(node, scope, Field.newUnqualified("rows", BIGINT));
        }

        @Override
        protected Scope visitCreateCube(CreateCube node, Optional<Scope> scope)
        {
            QualifiedObjectName targetCube = createQualifiedObjectName(session, node, node.getCubeName());

            List<Property> properties = node.getProperties();
            for (Property property : properties) {
                if (property.getName().getValue().equalsIgnoreCase("transactional") && property.getValue().toString().equalsIgnoreCase("true")) {
                    throw new SemanticException(NOT_SUPPORTED, node, "%s is a star-tree cube with transactional = true is not supported", node.getCubeName());
                }
            }

            CatalogName catalogName = metadata.getCatalogHandle(session, targetCube.getCatalogName())
                    .orElseThrow(() -> new PrestoException(NOT_FOUND, "Catalog not found: " + targetCube.getCatalogName()));
            if (!metadata.isPreAggregationSupported(session, catalogName)) {
                throw new PrestoException(StandardErrorCode.NOT_SUPPORTED, String.format("Cube cannot created on catalog '%s'", catalogName.toString()));
            }
            Optional<CubeMetaStore> optionalCubeMetaStore = cubeManager.getMetaStore(STAR_TREE);
            if (!optionalCubeMetaStore.isPresent()) {
                throw new RuntimeException("HetuMetaStore is not initialized");
            }

            analysis.setUpdateType("CREATE CUBE", targetCube);
            Set<Identifier> allIdentifiers = new HashSet<>();
            String duplicates = node.getGroupingSet().stream()
                    .filter(col -> !allIdentifiers.add(col))
                    .map(Identifier::toString)
                    .collect(joining(","));

            if (duplicates.length() > 0) {
                throw new SemanticException(DUPLICATE_COLUMN_NAME, node, "Columns %s specified more than once", duplicates);
            }

            Set<String> cubeSupportedFunctions = CubeAggregateFunction.SUPPORTED_FUNCTIONS;
            Set<FunctionCall> aggFunctions = node.getAggregations();
            Scope queryScope = process(new Table(node.getSourceTableName()), scope);
            ImmutableList.Builder<Field> outputFields = ImmutableList.builder();
            for (FunctionCall aggFunction : aggFunctions) {
                //count(1) or count(col)
                String argument = aggFunction.getArguments().isEmpty() || aggFunction.getArguments().get(0) instanceof LongLiteral ? null : ((Identifier) aggFunction.getArguments().get(0)).getValue();
                String aggFunctionName = aggFunction.getName().toString().toLowerCase(ENGLISH);
                if (!cubeSupportedFunctions.contains(aggFunctionName)) {
                    throw new SemanticException(NOT_SUPPORTED, node, "Unsupported aggregation function '%s'. Supported functions are '%s'", aggFunctionName, String.join("'", cubeSupportedFunctions));
                }
                if (aggFunction.getArguments().size() > 1) {
                    throw new SemanticException(TOO_MANY_ARGUMENTS, node, "Too many arguments for aggregate function '%s'", aggFunctionName);
                }
                if (aggFunction.isDistinct() && !aggFunctionName.equals(CubeAggregateFunction.COUNT.getName())) {
                    throw new SemanticException(NOT_SUPPORTED, node, "Distinct is currently only supported for count");
                }

                if (argument != null) {
                    ExpressionAnalysis expressionAnalysis = analyzeExpression(aggFunction, queryScope);
                    Type expressionType = expressionAnalysis.getType(aggFunction);
                    outputFields.add(Field.newUnqualified(aggFunctionName + "_" + argument + (aggFunction.isDistinct() ? "_distinct" : ""), expressionType));
                }
                else {
                    outputFields.add(Field.newUnqualified(aggFunctionName + "_" + "all" + (aggFunction.isDistinct() ? "_distinct" : ""), BIGINT));
                }
            }
            Optional<Expression> sourceFilterPredicate = node.getSourceFilter();
            sourceFilterPredicate.ifPresent(predicate -> {
                Set<Identifier> predicateColumns = ExpressionUtils.getIdentifiers(predicate);
                node.getGroupingSet().stream()
                        .filter(predicateColumns::contains)
                        .findFirst()
                        .ifPresent(identifier -> {
                            throw new SemanticException(NOT_SUPPORTED, node, "Column '%s' not allowed in source filter predicate. Source filter predicate cannot contain any of the columns defined in Group property", identifier);
                        });
                //analyze expression to identify if coercions required
                ExpressionAnalysis filterAnalysis = analyzeExpression(predicate, queryScope);
                Type predicateType = filterAnalysis.getType(predicate);
                if (!predicateType.equals(BOOLEAN) && !predicateType.equals(UNKNOWN)) {
                    throw new SemanticException(TYPE_MISMATCH, predicate, "Filter property must evaluate to a boolean: actual type '%s'", predicateType);
                }
            });
            return createAndAssignScope(node, scope, outputFields.build());
        }

        @Override
        protected Scope visitCreateTableAsSelect(CreateTableAsSelect node, Optional<Scope> scope)
        {
            // turn this into a query that has a new table writer node on top.
            QualifiedObjectName targetTable = createQualifiedObjectName(session, node, node.getName());
            analysis.setCreateTableDestination(targetTable);
            analysis.setUpdateType("CREATE TABLE", targetTable);

            Optional<TableHandle> targetTableHandle = metadata.getTableHandle(session, targetTable);
            if (targetTableHandle.isPresent()) {
                if (node.isNotExists()) {
                    analysis.setCreateTableAsSelectNoOp(true, targetTableHandle.get());
                    return createAndAssignScope(node, scope, Field.newUnqualified("rows", BIGINT));
                }
                throw new SemanticException(TABLE_ALREADY_EXISTS, node, "Destination table '%s' already exists", targetTable);
            }

            validateProperties(node.getProperties(), scope);
            analysis.setCreateTableProperties(mapFromProperties(node.getProperties()));

            node.getColumnAliases().ifPresent(analysis::setCreateTableColumnAliases);
            analysis.setCreateTableComment(node.getComment());

            accessControl.checkCanCreateTable(session.getRequiredTransactionId(), session.getIdentity(), targetTable);

            analysis.setCreateTableAsSelectWithData(node.isWithData());

            // analyze the query that creates the table
            Scope queryScope = process(node.getQuery(), scope);

            if (node.getColumnAliases().isPresent()) {
                validateColumnAliases(node.getColumnAliases().get(), queryScope.getRelationType().getVisibleFieldCount());

                // analzie only column types in subquery if column alias exists
                for (Field field : queryScope.getRelationType().getVisibleFields()) {
                    if (field.getType().equals(UNKNOWN)) {
                        throw new SemanticException(COLUMN_TYPE_UNKNOWN, node, "Column type is unknown at position %s", queryScope.getRelationType().indexOf(field) + 1);
                    }
                }
            }
            else {
                validateColumns(node, queryScope.getRelationType());
            }

            return createAndAssignScope(node, scope, Field.newUnqualified("rows", BIGINT));
        }

        @Override
        protected Scope visitCreateView(CreateView node, Optional<Scope> scope)
        {
            QualifiedObjectName viewName = createQualifiedObjectName(session, node, node.getName());
            analysis.setUpdateType("CREATE VIEW", viewName);

            // analyze the query that creates the view
            StatementAnalyzer analyzer = new StatementAnalyzer(analysis, metadata, sqlParser, accessControl, session, warningCollector);

            Scope queryScope = analyzer.analyze(node.getQuery(), scope);

            accessControl.checkCanCreateView(session.getRequiredTransactionId(), session.getIdentity(), viewName);

            validateColumns(node, queryScope.getRelationType());

            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitSetSession(SetSession node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        public Scope visitAssignmentItem(AssignmentItem node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        public Scope visitVacuumTable(VacuumTable node, Optional<Scope> scope)
        {
            Table table = node.getTable();
            QualifiedObjectName tableName = createQualifiedObjectName(session, table, table.getName());
            if (metadata.getView(session, tableName).isPresent()) {
                throw new SemanticException(NOT_SUPPORTED, node, "Vacuuming view is not supported");
            }

            // verify the existing of table
            Optional<TableHandle> targetTableHandle = metadata.getTableHandle(session, tableName);
            if (!targetTableHandle.isPresent()) {
                throw new SemanticException(MISSING_TABLE, node, "Table '%s' does not exist", tableName);
            }

            process(table, scope);
            analysis.setUpdateType("VACUUM", tableName);

            analysis.setAsyncQuery(node.isAsync());
            // check access right
            //TODO: ACL check
            return createAndAssignScope(node, scope, Field.newUnqualified("rows", BIGINT));
        }

        @Override
        protected Scope visitResetSession(ResetSession node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitAddColumn(AddColumn node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitCreateSchema(CreateSchema node, Optional<Scope> scope)
        {
            validateProperties(node.getProperties(), scope);
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDropSchema(DropSchema node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitRenameSchema(RenameSchema node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitCreateTable(CreateTable node, Optional<Scope> scope)
        {
            validateProperties(node.getProperties(), scope);
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitProperty(Property node, Optional<Scope> scope)
        {
            // Property value expressions must be constant
            createConstantAnalyzer(metadata, session, analysis.getParameters(), WarningCollector.NOOP, analysis.isDescribe())
                    .analyze(node.getValue(), createScope(scope));
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitCreateIndex(CreateIndex node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitFunctionProperty(FunctionProperty node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDropCache(DropCache node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDropTable(DropTable node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDropCube(DropCube node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitRenameTable(RenameTable node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDropIndex(DropIndex node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitUpdateIndex(UpdateIndex node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitComment(Comment node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitRenameColumn(RenameColumn node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDropColumn(DropColumn node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDropView(DropView node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitStartTransaction(StartTransaction node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitCommit(Commit node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitRollback(Rollback node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitPrepare(Prepare node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDeallocate(Deallocate node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitExecute(Execute node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitGrant(Grant node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitRevoke(Revoke node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitCall(Call node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        private void validateProperties(List<Property> properties, Optional<Scope> scope)
        {
            Set<String> propertyNames = new HashSet<>();
            for (Property property : properties) {
                if (!propertyNames.add(property.getName().getValue())) {
                    throw new SemanticException(DUPLICATE_PROPERTY, property, "Duplicate property: %s", property.getName().getValue());
                }
            }
            for (Property property : properties) {
                process(property, scope);
            }
        }

        private void validateColumns(Statement node, RelationType descriptor)
        {
            // verify that all column names are specified and unique
            // TODO: collect errors and return them all at once
            Set<String> names = new HashSet<>();
            for (Field field : descriptor.getVisibleFields()) {
                Optional<String> fieldName = field.getName();
                if (!fieldName.isPresent()) {
                    throw new SemanticException(COLUMN_NAME_NOT_SPECIFIED, node, "Column name not specified at position %s", descriptor.indexOf(field) + 1);
                }
                if (!names.add(fieldName.get())) {
                    throw new SemanticException(DUPLICATE_COLUMN_NAME, node, "Column name '%s' specified more than once", fieldName.get());
                }
                if (field.getType().equals(UNKNOWN)) {
                    throw new SemanticException(COLUMN_TYPE_UNKNOWN, node, "Column type is unknown: %s", fieldName.get());
                }
            }
        }

        private void validateColumnAliases(List<Identifier> columnAliases, int sourceColumnSize)
        {
            if (columnAliases.size() != sourceColumnSize) {
                throw new SemanticException(
                        MISMATCHED_COLUMN_ALIASES,
                        columnAliases.get(0),
                        "Column alias list has %s entries but subquery has %s columns",
                        columnAliases.size(),
                        sourceColumnSize);
            }
            Set<String> names = new HashSet<>();
            for (Identifier identifier : columnAliases) {
                if (names.contains(identifier.getValue().toLowerCase(ENGLISH))) {
                    throw new SemanticException(DUPLICATE_COLUMN_NAME, identifier, "Column name '%s' specified more than once", identifier.getValue());
                }
                names.add(identifier.getValue().toLowerCase(ENGLISH));
            }
        }

        @Override
        protected Scope visitExplain(Explain node, Optional<Scope> scope)
                throws SemanticException
        {
            checkState(node.isAnalyze(), "Non analyze explain should be rewritten to Query");
            if (node.getOptions().stream().anyMatch(option -> !option.equals(new ExplainType(DISTRIBUTED)))) {
                throw new SemanticException(NOT_SUPPORTED, node, "EXPLAIN ANALYZE only supports TYPE DISTRIBUTED option");
            }
            process(node.getStatement(), scope);
            analysis.resetUpdateType();
            return createAndAssignScope(node, scope, Field.newUnqualified("Query Plan", VARCHAR));
        }

        @Override
        protected Scope visitQuery(Query node, Optional<Scope> scope)
        {
            Scope withScope = analyzeWith(node, scope);
            Scope queryBodyScope = process(node.getQueryBody(), withScope);

            List<Expression> orderByExpressions = emptyList();
            if (node.getOrderBy().isPresent()) {
                orderByExpressions = analyzeOrderBy(node, getSortItemsFromOrderBy(node.getOrderBy()), queryBodyScope);

                if (queryBodyScope.getOuterQueryParent().isPresent() && !node.getLimit().isPresent() && !node.getOffset().isPresent()) {
                    // not the root scope and ORDER BY is ineffective
                    analysis.markRedundantOrderBy(node.getOrderBy().get());
                    warningCollector.add(new PrestoWarning(REDUNDANT_ORDER_BY, "ORDER BY in subquery may have no effect"));
                }
            }
            analysis.setOrderByExpressions(node, orderByExpressions);

            if (node.getOffset().isPresent()) {
                analyzeOffset(node.getOffset().get());
            }

            if (node.getLimit().isPresent()) {
                boolean requiresOrderBy = analyzeLimit(node.getLimit().get());
                if (requiresOrderBy && !node.getOrderBy().isPresent()) {
                    throw new SemanticException(MISSING_ORDER_BY, node.getLimit().get(), "FETCH FIRST WITH TIES clause requires ORDER BY");
                }
            }

            // Input fields == Output fields
            analysis.setOutputExpressions(node, descriptorToFields(queryBodyScope));

            Scope queryScope = Scope.builder()
                    .withParent(withScope)
                    .withRelationType(RelationId.of(node), queryBodyScope.getRelationType())
                    .build();

            analysis.setScope(node, queryScope);
            return queryScope;
        }

        @Override
        protected Scope visitUnnest(Unnest node, Optional<Scope> scope)
        {
            ImmutableList.Builder<Field> outputFields = ImmutableList.builder();
            for (Expression expression : node.getExpressions()) {
                ExpressionAnalysis expressionAnalysis = analyzeExpression(expression, createScope(scope));
                Type expressionType = expressionAnalysis.getType(expression);
                if (expressionType instanceof ArrayType) {
                    Type elementType = ((ArrayType) expressionType).getElementType();
                    if (elementType instanceof RowType) {
                        ((RowType) elementType).getFields().stream()
                                .map(field -> Field.newUnqualified(field.getName(), field.getType()))
                                .forEach(outputFields::add);
                    }
                    else {
                        outputFields.add(Field.newUnqualified(Optional.empty(), elementType));
                    }
                }
                else if (expressionType instanceof MapType) {
                    outputFields.add(Field.newUnqualified(Optional.empty(), ((MapType) expressionType).getKeyType()));
                    outputFields.add(Field.newUnqualified(Optional.empty(), ((MapType) expressionType).getValueType()));
                }
                else {
                    throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Cannot unnest type: " + expressionType);
                }
            }
            if (node.isWithOrdinality()) {
                outputFields.add(Field.newUnqualified(Optional.empty(), BIGINT));
            }
            return createAndAssignScope(node, scope, outputFields.build());
        }

        @Override
        protected Scope visitLateral(Lateral node, Optional<Scope> scope)
        {
            StatementAnalyzer analyzer = new StatementAnalyzer(analysis, metadata, sqlParser, accessControl, session, warningCollector);
            Scope queryScope = analyzer.analyze(node.getQuery(), scope);
            return createAndAssignScope(node, scope, queryScope.getRelationType());
        }

        @Override
        protected Scope visitTable(Table table, Optional<Scope> scope)
        {
            if (analysis.getOriginalStatement() instanceof CreateIndex) {
                validateCreateIndex(table, scope);
            }

            if (analysis.getOriginalStatement() instanceof UpdateIndex) {
                validateUpdateIndex(table, scope);
            }

            if (!table.getName().getPrefix().isPresent()) {
                // is this a reference to a WITH query?
                String name = table.getName().getSuffix();

                Optional<WithQuery> withQuery = createScope(scope).getNamedQuery(name);
                if (withQuery.isPresent()) {
                    Query query = withQuery.get().getQuery();
                    analysis.registerNamedQuery(table, query);

                    // re-alias the fields with the name assigned to the query in the WITH declaration
                    RelationType queryDescriptor = analysis.getOutputDescriptor(query);

                    List<Field> fields;
                    Optional<List<Identifier>> columnNames = withQuery.get().getColumnNames();
                    if (columnNames.isPresent()) {
                        // if columns are explicitly aliased -> WITH cte(alias1, alias2 ...)
                        ImmutableList.Builder<Field> fieldBuilder = ImmutableList.builder();

                        int field = 0;
                        for (Identifier columnName : columnNames.get()) {
                            Field inputField = queryDescriptor.getFieldByIndex(field);
                            fieldBuilder.add(Field.newQualified(
                                    QualifiedName.of(name),
                                    Optional.of(columnName.getValue()),
                                    inputField.getType(),
                                    false,
                                    inputField.getOriginTable(),
                                    inputField.getOriginColumnName(),
                                    inputField.isAliased()));

                            field++;
                        }

                        fields = fieldBuilder.build();
                    }
                    else {
                        fields = queryDescriptor.getAllFields().stream()
                                .map(field -> Field.newQualified(
                                        QualifiedName.of(name),
                                        field.getName(),
                                        field.getType(),
                                        field.isHidden(),
                                        field.getOriginTable(),
                                        field.getOriginColumnName(),
                                        field.isAliased()))
                                .collect(toImmutableList());
                    }

                    return createAndAssignScope(table, scope, fields);
                }
            }

            QualifiedObjectName name = createQualifiedObjectName(session, table, table.getName());
            analysis.addEmptyColumnReferencesForTable(accessControl, session.getIdentity(), name);

            // This section of code is used to load DC sub-catalogs dynamically
            DataCenterUtility.loadDCCatalogForQueryFlow(session, metadata, name.getCatalogName());

            Optional<ConnectorViewDefinition> optionalView = metadata.getView(session, name);
            if (optionalView.isPresent()) {
                Statement statement = analysis.getStatement();
                if (statement instanceof CreateView) {
                    CreateView viewStatement = (CreateView) statement;
                    QualifiedObjectName viewNameFromStatement = createQualifiedObjectName(session, viewStatement, viewStatement.getName());
                    if (viewStatement.isReplace() && viewNameFromStatement.equals(name)) {
                        throw new SemanticException(VIEW_IS_RECURSIVE, table, "Statement would create a recursive view");
                    }
                }
                if (analysis.hasTableInView(table)) {
                    throw new SemanticException(VIEW_IS_RECURSIVE, table, "View is recursive");
                }
                ConnectorViewDefinition view = optionalView.get();

                Query query = parseView(view.getOriginalSql(), name, table);

                analysis.registerNamedQuery(table, query);

                analysis.registerTableForView(table);
                RelationType descriptor = analyzeView(query, name, view.getCatalog(), view.getSchema(), view.getOwner(), table);
                analysis.unregisterTableForView();

                if (isViewStale(view.getColumns(), descriptor.getVisibleFields(), name, table)) {
                    throw new SemanticException(VIEW_IS_STALE, table, "View '%s' is stale; it must be re-created", name);
                }

                // Derive the type of the view from the stored definition, not from the analysis of the underlying query.
                // This is needed in case the underlying table(s) changed and the query in the view now produces types that
                // are implicitly coercible to the declared view types.
                List<Field> outputFields = view.getColumns().stream()
                        .map(column -> Field.newQualified(
                                table.getName(),
                                Optional.of(column.getName()),
                                getViewColumnType(column, name, table),
                                false,
                                Optional.of(name),
                                Optional.of(column.getName()),
                                false))
                        .collect(toImmutableList());

                analysis.addRelationCoercion(table, outputFields.stream().map(Field::getType).toArray(Type[]::new));

                Scope accessControlScope = Scope.builder()
                        .withRelationType(RelationId.anonymous(), new RelationType(outputFields))
                        .build();

                for (Field field : outputFields) {
                    accessControl.getColumnMasks(session.getTransactionId().get(), session.getIdentity(), name, field.getName().get(), field.getType())
                            .forEach(mask -> analyzeColumnMask(session.getIdentity().getUser(), table, name, field, accessControlScope, mask));
                }

                accessControl.getRowFilters(session.getTransactionId().get(), session.getIdentity(), name)
                        .forEach(filter -> analyzeRowFilter(session.getIdentity().getUser(), table, name, accessControlScope, filter));

                return createAndAssignScope(table, scope, outputFields);
            }

            Optional<TableHandle> tableHandle = metadata.getTableHandle(session, name);
            if (!tableHandle.isPresent()) {
                if (!metadata.getCatalogHandle(session, name.getCatalogName()).isPresent()) {
                    throw new SemanticException(MISSING_CATALOG, table, "Catalog %s does not exist", name.getCatalogName());
                }
                if (!metadata.schemaExists(session, new CatalogSchemaName(name.getCatalogName(), name.getSchemaName()))) {
                    throw new SemanticException(MISSING_SCHEMA, table, "Schema %s does not exist", name.getSchemaName());
                }
                throw new SemanticException(MISSING_TABLE, table, "Table %s does not exist", name);
            }
            TableMetadata tableMetadata = metadata.getTableMetadata(session, tableHandle.get());
            Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, tableHandle.get());

            // TODO: discover columns lazily based on where they are needed (to support connectors that can't enumerate all tables)
            ImmutableList.Builder<Field> fields = ImmutableList.builder();
            for (ColumnMetadata column : tableMetadata.getColumns()) {
                Field field = Field.newQualified(
                        table.getName(),
                        Optional.of(column.getName()),
                        column.getType(),
                        column.isHidden(),
                        Optional.of(name),
                        Optional.of(column.getName()),
                        false);
                fields.add(field);
                ColumnHandle columnHandle = columnHandles.get(column.getName());
                checkArgument(columnHandle != null, "Unknown field %s", field);
                analysis.setColumn(field, columnHandle);
            }

            List<Field> outputFields = fields.build();

            Scope accessControlScope = Scope.builder()
                    .withRelationType(RelationId.anonymous(), new RelationType(outputFields))
                    .build();

            for (Field field : outputFields) {
                accessControl.getColumnMasks(session.getTransactionId().get(), session.getIdentity(), name, field.getName().get(), field.getType())
                        .forEach(mask -> analyzeColumnMask(session.getIdentity().getUser(), table, name, field, accessControlScope, mask));
            }

            accessControl.getRowFilters(session.getTransactionId().get(), session.getIdentity(), name)
                    .forEach(filter -> analyzeRowFilter(session.getIdentity().getUser(), table, name, accessControlScope, filter));

            analysis.registerTable(table, tableHandle.get());

            if (updateKind.isPresent()) {
                // Add the row id field
                ColumnHandle rowIdColumnHandle;
                switch (updateKind.get()) {
                    case DELETE:
                        rowIdColumnHandle = metadata.getDeleteRowIdColumnHandle(session, tableHandle.get());
                        break;
                    case UPDATE:
                        List<ColumnMetadata> updatedColumnMetadata = analysis.getUpdatedColumns()
                                .orElseThrow(() -> new VerifyException("updated columns not set"));
                        Set<String> updatedColumnNames = updatedColumnMetadata.stream().map(ColumnMetadata::getName).collect(toImmutableSet());
                        List<ColumnHandle> updatedColumns = columnHandles.entrySet().stream()
                                .filter(entry -> updatedColumnNames.contains(entry.getKey()))
                                .map(Map.Entry::getValue)
                                .collect(toImmutableList());
                        rowIdColumnHandle = metadata.getUpdateRowIdColumnHandle(session, tableHandle.get(), updatedColumns);
                        break;
                    default:
                        throw new UnsupportedOperationException("Unknown UpdateKind " + updateKind.get());
                }

                Type type = metadata.getColumnMetadata(session, tableHandle.get(), rowIdColumnHandle).getType();
                Field field = Field.newUnqualified(rowIdColumnHandle.getColumnName(), type);
                fields.add(field);
                analysis.setColumn(field, rowIdColumnHandle);
                analysis.setRowIdHandle(table, rowIdColumnHandle);
            }

            List<Field> newOutputFields = fields.build();
            Scope tableScope = createAndAssignScope(table, scope, newOutputFields);

            if (updateKind.isPresent()) {
                FieldReference reference = new FieldReference(newOutputFields.size() - 1);
                analyzeExpression(reference, tableScope);
                analysis.setRowIdField(table, reference);
            }

            return tableScope;
        }

        @Override
        protected Scope visitAliasedRelation(AliasedRelation relation, Optional<Scope> scope)
        {
            Scope relationScope = process(relation.getRelation(), scope);

            // todo this check should be inside of TupleDescriptor.withAlias, but the exception needs the node object
            RelationType relationType = relationScope.getRelationType();
            if (relation.getColumnNames() != null) {
                int totalColumns = relationType.getVisibleFieldCount();
                if (totalColumns != relation.getColumnNames().size()) {
                    throw new SemanticException(MISMATCHED_COLUMN_ALIASES, relation, "Column alias list has %s entries but '%s' has %s columns available", relation.getColumnNames().size(), relation.getAlias(), totalColumns);
                }
            }

            List<String> aliases = null;
            if (relation.getColumnNames() != null) {
                aliases = relation.getColumnNames().stream()
                        .map(Identifier::getValue)
                        .collect(Collectors.toList());
            }

            RelationType descriptor = relationType.withAlias(relation.getAlias().getValue(), aliases);

            return createAndAssignScope(relation, scope, descriptor);
        }

        @Override
        protected Scope visitSampledRelation(SampledRelation relation, Optional<Scope> scope)
        {
            if (!SymbolsExtractor.extractNames(relation.getSamplePercentage(), analysis.getColumnReferences()).isEmpty()) {
                throw new SemanticException(NON_NUMERIC_SAMPLE_PERCENTAGE, relation.getSamplePercentage(), "Sample percentage cannot contain column references");
            }

            Map<NodeRef<Expression>, Type> expressionTypes = ExpressionAnalyzer.analyzeExpressions(
                    session,
                    metadata,
                    sqlParser,
                    TypeProvider.empty(),
                    ImmutableList.of(relation.getSamplePercentage()),
                    analysis.getParameters(),
                    WarningCollector.NOOP,
                    analysis.isDescribe())
                    .getExpressionTypes();

            ExpressionInterpreter samplePercentageEval = expressionOptimizer(relation.getSamplePercentage(), metadata, session, expressionTypes);

            Object samplePercentageObject = samplePercentageEval.optimize(symbol -> {
                throw new SemanticException(NON_NUMERIC_SAMPLE_PERCENTAGE, relation.getSamplePercentage(), "Sample percentage cannot contain column references");
            });

            if (!(samplePercentageObject instanceof Number)) {
                throw new SemanticException(NON_NUMERIC_SAMPLE_PERCENTAGE, relation.getSamplePercentage(), "Sample percentage should evaluate to a numeric expression");
            }

            double samplePercentageValue = ((Number) samplePercentageObject).doubleValue();

            if (samplePercentageValue < 0.0) {
                throw new SemanticException(SemanticErrorCode.SAMPLE_PERCENTAGE_OUT_OF_RANGE, relation.getSamplePercentage(), "Sample percentage must be greater than or equal to 0");
            }
            if ((samplePercentageValue > 100.0)) {
                throw new SemanticException(SemanticErrorCode.SAMPLE_PERCENTAGE_OUT_OF_RANGE, relation.getSamplePercentage(), "Sample percentage must be less than or equal to 100");
            }

            analysis.setSampleRatio(relation, samplePercentageValue / 100);
            Scope relationScope = process(relation.getRelation(), scope);
            return createAndAssignScope(relation, scope, relationScope.getRelationType());
        }

        @Override
        protected Scope visitTableSubquery(TableSubquery node, Optional<Scope> scope)
        {
            StatementAnalyzer analyzer = new StatementAnalyzer(analysis, metadata, sqlParser, accessControl, session, warningCollector);
            Scope queryScope = analyzer.analyze(node.getQuery(), scope);
            return createAndAssignScope(node, scope, queryScope.getRelationType());
        }

        @Override
        protected Scope visitQuerySpecification(QuerySpecification node, Optional<Scope> scope)
        {
            // TODO: extract candidate names from SELECT, WHERE, HAVING, GROUP BY and ORDER BY expressions
            // to pass down to analyzeFrom

            Scope sourceScope = analyzeFrom(node, scope);

            node.getWhere().ifPresent(where -> analyzeWhere(node, sourceScope, where));

            List<Expression> outputExpressions = analyzeSelect(node, sourceScope);
            List<Expression> groupByExpressions = analyzeGroupBy(node, sourceScope, outputExpressions);
            analyzeHaving(node, sourceScope);

            Scope outputScope = computeAndAssignOutputScope(node, scope, sourceScope);

            List<Expression> orderByExpressions = emptyList();
            Optional<Scope> orderByScope = Optional.empty();
            if (node.getOrderBy().isPresent()) {
                if (node.getSelect().isDistinct()) {
                    verifySelectDistinct(node, outputExpressions);
                }

                OrderBy orderBy = node.getOrderBy().get();
                orderByScope = Optional.of(computeAndAssignOrderByScope(orderBy, sourceScope, outputScope));

                orderByExpressions = analyzeOrderBy(node, orderBy.getSortItems(), orderByScope.get());

                if (sourceScope.getOuterQueryParent().isPresent() && !node.getLimit().isPresent() && !node.getOffset().isPresent()) {
                    // not the root scope and ORDER BY is ineffective
                    analysis.markRedundantOrderBy(orderBy);
                    warningCollector.add(new PrestoWarning(REDUNDANT_ORDER_BY, "ORDER BY in subquery may have no effect"));
                }
            }
            analysis.setOrderByExpressions(node, orderByExpressions);

            if (node.getOffset().isPresent()) {
                analyzeOffset(node.getOffset().get());
            }

            if (node.getLimit().isPresent()) {
                boolean requiresOrderBy = analyzeLimit(node.getLimit().get());
                if (requiresOrderBy && !node.getOrderBy().isPresent()) {
                    throw new SemanticException(MISSING_ORDER_BY, node.getLimit().get(), "FETCH FIRST WITH TIES clause requires ORDER BY");
                }
            }

            List<Expression> sourceExpressions = new ArrayList<>(outputExpressions);
            node.getHaving().ifPresent(sourceExpressions::add);

            analyzeGroupingOperations(node, sourceExpressions, orderByExpressions);
            analyzeAggregations(node, sourceScope, orderByScope, groupByExpressions, sourceExpressions, orderByExpressions);
            analyzeWindowFunctions(node, outputExpressions, orderByExpressions);

            if (analysis.isAggregation(node) && node.getOrderBy().isPresent()) {
                // Create a different scope for ORDER BY expressions when aggregation is present.
                // This is because planner requires scope in order to resolve names against fields.
                // Original ORDER BY scope "sees" FROM query fields. However, during planning
                // and when aggregation is present, ORDER BY expressions should only be resolvable against
                // output scope, group by expressions and aggregation expressions.
                List<GroupingOperation> orderByGroupingOperations = extractExpressions(orderByExpressions, GroupingOperation.class);
                List<FunctionCall> orderByAggregations = extractAggregateFunctions(orderByExpressions, metadata);
                computeAndAssignOrderByScopeWithAggregation(node.getOrderBy().get(), sourceScope, outputScope, orderByAggregations, groupByExpressions, orderByGroupingOperations);
            }

            //visit cubes associated with original Table
            if (isEnableStarTreeIndex(session) && hasAggregates(node)) {
                Collection<TableHandle> tableHandles = analysis.getTables();
                tableHandles.forEach(this::analyzeCubes);
            }

            return outputScope;
        }

        private void analyzeCubes(TableHandle originalTable)
        {
            if (cubeManager == null || !cubeManager.getMetaStore(STAR_TREE).isPresent()) {
                //Skip if aggregation was part of subqueries and expressions, etc..
                //StarTreeAggregation optimizer would be skipped as well
                return;
            }
            CubeMetaStore cubeMetaStore = cubeManager.getMetaStore(STAR_TREE).get();
            List<CubeMetadata> cubeMetadataList = cubeMetaStore.getMetadataList(originalTable.getFullyQualifiedName());
            for (CubeMetadata cubeMetadata : cubeMetadataList) {
                QualifiedObjectName cubeName = QualifiedObjectName.valueOf(cubeMetadata.getCubeName());
                Optional<TableHandle> cubeHandle = metadata.getTableHandle(session, cubeName);
                cubeHandle.ifPresent(cubeTH -> analysis.registerCubeForTable(originalTable, cubeTH));
                if (!cubeHandle.isPresent()) {
                    warningCollector.add(new PrestoWarning(CUBE_NOT_FOUND, String.format("Cube with name '%s' not found.", cubeName)));
                }
            }
        }

        @Override
        protected Scope visitSetOperation(SetOperation node, Optional<Scope> scope)
        {
            checkState(node.getRelations().size() >= 2);

            List<Scope> relationScopes = node.getRelations().stream()
                    .map(relation -> {
                        Scope relationScope = process(relation, scope);
                        return createAndAssignScope(relation, scope, relationScope.getRelationType().withOnlyVisibleFields());
                    })
                    .collect(toImmutableList());

            Type[] outputFieldTypes = relationScopes.get(0).getRelationType().getVisibleFields().stream()
                    .map(Field::getType)
                    .toArray(Type[]::new);
            for (Scope relationScope : relationScopes) {
                int outputFieldSize = outputFieldTypes.length;
                RelationType relationType = relationScope.getRelationType();
                int descFieldSize = relationType.getVisibleFields().size();
                String setOperationName = node.getClass().getSimpleName().toUpperCase(ENGLISH);
                if (outputFieldSize != descFieldSize) {
                    throw new SemanticException(
                            MISMATCHED_SET_COLUMN_TYPES,
                            node,
                            "%s query has different number of fields: %d, %d",
                            setOperationName,
                            outputFieldSize,
                            descFieldSize);
                }
                for (int i = 0; i < descFieldSize; i++) {
                    Type descFieldType = relationType.getFieldByIndex(i).getType();
                    Optional<Type> commonSuperType = typeCoercion.getCommonSuperType(outputFieldTypes[i], descFieldType);
                    if (!commonSuperType.isPresent()) {
                        throw new SemanticException(
                                TYPE_MISMATCH,
                                node,
                                "column %d in %s query has incompatible types: %s, %s",
                                i + 1,
                                setOperationName,
                                outputFieldTypes[i].getDisplayName(),
                                descFieldType.getDisplayName());
                    }
                    outputFieldTypes[i] = commonSuperType.get();
                }
            }

            Field[] outputDescriptorFields = new Field[outputFieldTypes.length];
            RelationType firstDescriptor = relationScopes.get(0).getRelationType().withOnlyVisibleFields();
            for (int i = 0; i < outputFieldTypes.length; i++) {
                Field oldField = firstDescriptor.getFieldByIndex(i);
                outputDescriptorFields[i] = new Field(
                        oldField.getRelationAlias(),
                        oldField.getName(),
                        outputFieldTypes[i],
                        oldField.isHidden(),
                        oldField.getOriginTable(),
                        oldField.getOriginColumnName(),
                        oldField.isAliased());
            }

            for (int i = 0; i < node.getRelations().size(); i++) {
                Relation relation = node.getRelations().get(i);
                Scope relationScope = relationScopes.get(i);
                RelationType relationType = relationScope.getRelationType();
                for (int j = 0; j < relationType.getVisibleFields().size(); j++) {
                    Type outputFieldType = outputFieldTypes[j];
                    Type descFieldType = relationType.getFieldByIndex(j).getType();
                    if (!outputFieldType.equals(descFieldType)) {
                        analysis.addRelationCoercion(relation, outputFieldTypes);
                        break;
                    }
                }
            }
            return createAndAssignScope(node, scope, outputDescriptorFields);
        }

        @Override
        protected Scope visitIntersect(Intersect node, Optional<Scope> scope)
        {
            if (!node.isDistinct()) {
                throw new SemanticException(NOT_SUPPORTED, node, "INTERSECT ALL not yet implemented");
            }

            return visitSetOperation(node, scope);
        }

        @Override
        protected Scope visitExcept(Except node, Optional<Scope> scope)
        {
            if (!node.isDistinct()) {
                throw new SemanticException(NOT_SUPPORTED, node, "EXCEPT ALL not yet implemented");
            }

            return visitSetOperation(node, scope);
        }

        @Override
        protected Scope visitJoin(Join node, Optional<Scope> scope)
        {
            JoinCriteria criteria = node.getCriteria().orElse(null);
            if (criteria instanceof NaturalJoin) {
                throw new SemanticException(NOT_SUPPORTED, node, "Natural join not supported");
            }

            Scope left = process(node.getLeft(), scope);
            Scope right = process(node.getRight(), isLateralRelation(node.getRight()) ? Optional.of(left) : scope);

            if (criteria instanceof JoinUsing) {
                return analyzeJoinUsing(node, ((JoinUsing) criteria).getColumns(), scope, left, right);
            }

            Scope output = createAndAssignScope(node, scope, left.getRelationType().joinWith(right.getRelationType()));

            if (node.getType() == Join.Type.CROSS || node.getType() == Join.Type.IMPLICIT) {
                return output;
            }
            if (criteria instanceof JoinOn) {
                Expression expression = ((JoinOn) criteria).getExpression();

                // need to register coercions in case when join criteria requires coercion (e.g. join on char(1) = char(2))
                ExpressionAnalysis expressionAnalysis = analyzeExpression(expression, output);
                Type clauseType = expressionAnalysis.getType(expression);
                if (!clauseType.equals(BOOLEAN)) {
                    if (!clauseType.equals(UNKNOWN)) {
                        throw new SemanticException(TYPE_MISMATCH, expression, "JOIN ON clause must evaluate to a boolean: actual type %s", clauseType);
                    }
                    // coerce null to boolean
                    analysis.addCoercion(expression, BOOLEAN, false);
                }

                verifyNoAggregateWindowOrGroupingFunctions(metadata, expression, "JOIN clause");

                analysis.recordSubqueries(node, expressionAnalysis);
                analysis.setJoinCriteria(node, expression);
            }
            else {
                throw new UnsupportedOperationException("unsupported join criteria: " + criteria.getClass().getName());
            }

            return output;
        }

        private Scope analyzeJoinUsing(Join node, List<Identifier> columns, Optional<Scope> scope, Scope left, Scope right)
        {
            List<Field> joinFields = new ArrayList<>();

            List<Integer> leftJoinFields = new ArrayList<>();
            List<Integer> rightJoinFields = new ArrayList<>();

            Set<Identifier> seen = new HashSet<>();
            for (Identifier column : columns) {
                if (!seen.add(column)) {
                    throw new SemanticException(DUPLICATE_COLUMN_NAME, column, "Column '%s' appears multiple times in USING clause", column.getValue());
                }

                Optional<ResolvedField> leftField = left.tryResolveField(column);
                Optional<ResolvedField> rightField = right.tryResolveField(column);

                if (!leftField.isPresent()) {
                    throw new SemanticException(MISSING_ATTRIBUTE, column, "Column '%s' is missing from left side of join", column.getValue());
                }
                if (!rightField.isPresent()) {
                    throw new SemanticException(MISSING_ATTRIBUTE, column, "Column '%s' is missing from right side of join", column.getValue());
                }

                // ensure a comparison operator exists for the given types (applying coercions if necessary)
                try {
                    metadata.getFunctionAndTypeManager().resolveOperator(OperatorType.EQUAL, ImmutableList.of(
                            leftField.get().getType(), rightField.get().getType()));
                }
                catch (OperatorNotFoundException e) {
                    throw new SemanticException(TYPE_MISMATCH, column, "%s", e.getMessage());
                }

                Optional<Type> type = typeCoercion.getCommonSuperType(leftField.get().getType(), rightField.get().getType());
                analysis.addTypes(ImmutableMap.of(NodeRef.of(column), type.get()));

                joinFields.add(Field.newUnqualified(column.getValue(), type.get()));

                leftJoinFields.add(leftField.get().getRelationFieldIndex());
                rightJoinFields.add(rightField.get().getRelationFieldIndex());
            }

            ImmutableList.Builder<Field> outputs = ImmutableList.builder();
            outputs.addAll(joinFields);

            ImmutableList.Builder<Integer> leftFields = ImmutableList.builder();
            for (int i = 0; i < left.getRelationType().getAllFieldCount(); i++) {
                if (!leftJoinFields.contains(i)) {
                    outputs.add(left.getRelationType().getFieldByIndex(i));
                    leftFields.add(i);
                }
            }

            ImmutableList.Builder<Integer> rightFields = ImmutableList.builder();
            for (int i = 0; i < right.getRelationType().getAllFieldCount(); i++) {
                if (!rightJoinFields.contains(i)) {
                    outputs.add(right.getRelationType().getFieldByIndex(i));
                    rightFields.add(i);
                }
            }

            analysis.setJoinUsing(node, new Analysis.JoinUsingAnalysis(leftJoinFields, rightJoinFields, leftFields.build(), rightFields.build()));

            return createAndAssignScope(node, scope, new RelationType(outputs.build()));
        }

        private boolean isLateralRelation(Relation node)
        {
            if (node instanceof AliasedRelation) {
                return isLateralRelation(((AliasedRelation) node).getRelation());
            }
            return node instanceof Unnest || node instanceof Lateral;
        }

        @Override
        protected Scope visitValues(Values node, Optional<Scope> scope)
        {
            checkState(node.getRows().size() >= 1);

            List<List<Type>> rowTypes = node.getRows().stream()
                    .map(row -> analyzeExpression(row, createScope(scope)).getType(row))
                    .map(type -> {
                        if (type instanceof RowType) {
                            return type.getTypeParameters();
                        }
                        return ImmutableList.of(type);
                    })
                    .collect(toImmutableList());

            // determine common super type of the rows
            List<Type> fieldTypes = new ArrayList<>(rowTypes.iterator().next());
            for (List<Type> rowType : rowTypes) {
                // check field count consistency for rows
                if (rowType.size() != fieldTypes.size()) {
                    throw new SemanticException(MISMATCHED_SET_COLUMN_TYPES,
                            node,
                            "Values rows have mismatched types: %s vs %s",
                            rowTypes.get(0),
                            rowType);
                }

                for (int i = 0; i < rowType.size(); i++) {
                    Type fieldType = rowType.get(i);
                    Type superType = fieldTypes.get(i);

                    Optional<Type> commonSuperType = typeCoercion.getCommonSuperType(fieldType, superType);
                    if (!commonSuperType.isPresent()) {
                        throw new SemanticException(MISMATCHED_SET_COLUMN_TYPES,
                                node,
                                "Values rows have mismatched types: %s vs %s",
                                rowTypes.get(0),
                                rowType);
                    }
                    fieldTypes.set(i, commonSuperType.get());
                }
            }

            // add coercions for the rows
            for (Expression row : node.getRows()) {
                if (row instanceof Row) {
                    List<Expression> items = ((Row) row).getItems();
                    for (int i = 0; i < items.size(); i++) {
                        Type expectedType = fieldTypes.get(i);
                        Expression item = items.get(i);
                        Type actualType = analysis.getType(item);
                        if (!actualType.equals(expectedType)) {
                            analysis.addCoercion(item, expectedType, typeCoercion.isTypeOnlyCoercion(actualType, expectedType));
                        }
                    }
                }
                else {
                    Type actualType = analysis.getType(row);
                    Type expectedType = fieldTypes.get(0);
                    if (!actualType.equals(expectedType)) {
                        analysis.addCoercion(row, expectedType, typeCoercion.isTypeOnlyCoercion(actualType, expectedType));
                    }
                }
            }

            List<Field> fields = fieldTypes.stream()
                    .map(valueType -> Field.newUnqualified(Optional.empty(), valueType))
                    .collect(toImmutableList());

            return createAndAssignScope(node, scope, fields);
        }

        private void checkFunctionName(Statement node, QualifiedName functionName)
        {
            if (functionName.getParts().size() != 3) {
                throw new SemanticException(INVALID_FUNCTION_NAME, node, format("Function name should be in the form of catalog.schema.function_name, found: %s", functionName));
            }
        }

        private void analyzeWindowFunctions(QuerySpecification node, List<Expression> outputExpressions, List<Expression> orderByExpressions)
        {
            analysis.setWindowFunctions(node, analyzeWindowFunctions(node, outputExpressions));
            if (node.getOrderBy().isPresent()) {
                analysis.setOrderByWindowFunctions(node.getOrderBy().get(), analyzeWindowFunctions(node, orderByExpressions));
            }
        }

        private List<FunctionCall> analyzeWindowFunctions(QuerySpecification node, List<Expression> expressions)
        {
            for (Expression expression : expressions) {
                new WindowFunctionValidator(metadata.getFunctionAndTypeManager()).process(expression, analysis);
            }

            List<FunctionCall> windowFunctions = extractWindowFunctions(expressions);

            for (FunctionCall windowFunction : windowFunctions) {
                // filter with window function is not supported yet
                if (windowFunction.getFilter().isPresent()) {
                    throw new SemanticException(NOT_SUPPORTED, node, "FILTER is not yet supported for window functions");
                }

                if (windowFunction.getOrderBy().isPresent()) {
                    throw new SemanticException(NOT_SUPPORTED, windowFunction, "Window function with ORDER BY is not supported");
                }

                Window window = windowFunction.getWindow().get();

                ImmutableList.Builder<Node> toExtract = ImmutableList.builder();
                toExtract.addAll(windowFunction.getArguments());
                toExtract.addAll(window.getPartitionBy());
                window.getOrderBy().ifPresent(orderBy -> toExtract.addAll(orderBy.getSortItems()));
                window.getFrame().ifPresent(toExtract::add);

                List<FunctionCall> nestedWindowFunctions = extractWindowFunctions(toExtract.build());

                if (!nestedWindowFunctions.isEmpty()) {
                    throw new SemanticException(NESTED_WINDOW, node, "Cannot nest window functions inside window function '%s': %s",
                            windowFunction,
                            windowFunctions);
                }

                if (windowFunction.isDistinct()) {
                    throw new SemanticException(NOT_SUPPORTED, node, "DISTINCT in window function parameters not yet supported: %s", windowFunction);
                }

                if (window.getFrame().isPresent()) {
                    analyzeWindowFrame(window.getFrame().get());
                }

                List<TypeSignature> argumentTypes = mappedCopy(windowFunction.getArguments(), expression -> analysis.getType(expression).getTypeSignature());

                FunctionKind kind = metadata.getFunctionAndTypeManager().getFunctionMetadata(analysis.getFunctionHandle(windowFunction)).getFunctionKind();
                if (kind != AGGREGATE && kind != WINDOW) {
                    throw new SemanticException(MUST_BE_WINDOW_FUNCTION, node, "Not a window function: %s", windowFunction.getName());
                }
            }

            return windowFunctions;
        }

        private void analyzeWindowFrame(WindowFrame frame)
        {
            Types.FrameBoundType startType = frame.getStart().getType();
            Types.FrameBoundType endType = frame.getEnd().orElse(new FrameBound(CURRENT_ROW)).getType();

            if (startType == UNBOUNDED_FOLLOWING) {
                throw new SemanticException(INVALID_WINDOW_FRAME, frame, "Window frame start cannot be UNBOUNDED FOLLOWING");
            }
            if (endType == UNBOUNDED_PRECEDING) {
                throw new SemanticException(INVALID_WINDOW_FRAME, frame, "Window frame end cannot be UNBOUNDED PRECEDING");
            }
            if ((startType == CURRENT_ROW) && (endType == PRECEDING)) {
                throw new SemanticException(INVALID_WINDOW_FRAME, frame, "Window frame starting from CURRENT ROW cannot end with PRECEDING");
            }
            if ((startType == FOLLOWING) && (endType == PRECEDING)) {
                throw new SemanticException(INVALID_WINDOW_FRAME, frame, "Window frame starting from FOLLOWING cannot end with PRECEDING");
            }
            if ((startType == FOLLOWING) && (endType == CURRENT_ROW)) {
                throw new SemanticException(INVALID_WINDOW_FRAME, frame, "Window frame starting from FOLLOWING cannot end with CURRENT ROW");
            }
            if ((frame.getType() == RANGE) && ((startType == PRECEDING) || (endType == PRECEDING))) {
                throw new SemanticException(INVALID_WINDOW_FRAME, frame, "Window frame RANGE PRECEDING is only supported with UNBOUNDED");
            }
            if ((frame.getType() == RANGE) && ((startType == FOLLOWING) || (endType == FOLLOWING))) {
                throw new SemanticException(INVALID_WINDOW_FRAME, frame, "Window frame RANGE FOLLOWING is only supported with UNBOUNDED");
            }
        }

        private void analyzeHaving(QuerySpecification node, Scope scope)
        {
            if (node.getHaving().isPresent()) {
                Expression predicate = node.getHaving().get();

                Map<String, Object> columnAliasMap = new HashMap<>();
                List<SelectItem> selectItemList = node.getSelect().getSelectItems();
                if (selectItemList.size() > 0) {
                    for (SelectItem si : selectItemList) {
                        if (si instanceof SingleColumn && (((SingleColumn) si).getAlias().isPresent())) {
                            Expression ex = ((SingleColumn) si).getExpression();
                            columnAliasMap.put(((SingleColumn) si).getAlias().get().getValue(), ex);
                        }
                    }
                }
                if (predicate instanceof ComparisonExpression) {
                    if (((ComparisonExpression) predicate).getLeft() instanceof Identifier) {
                        Expression leftExpr = (Expression) columnAliasMap.get(((Identifier) ((ComparisonExpression) predicate).getLeft()).getValue());
                        if (leftExpr != null) {
                            ((ComparisonExpression) predicate).setLeft(leftExpr);
                        }
                    }
                }

                ExpressionAnalysis expressionAnalysis = analyzeExpression(predicate, scope);

                expressionAnalysis.getWindowFunctions().stream()
                        .findFirst()
                        .ifPresent(function -> {
                            throw new SemanticException(NESTED_WINDOW, function.getNode(), "HAVING clause cannot contain window functions");
                        });

                analysis.recordSubqueries(node, expressionAnalysis);

                Type predicateType = expressionAnalysis.getType(predicate);
                if (!predicateType.equals(BOOLEAN) && !predicateType.equals(UNKNOWN)) {
                    throw new SemanticException(TYPE_MISMATCH, predicate, "HAVING clause must evaluate to a boolean: actual type %s", predicateType);
                }

                analysis.setHaving(node, predicate);
            }
        }

        private Multimap<QualifiedName, Expression> extractNamedOutputExpressions(Select node)
        {
            // Compute aliased output terms so we can resolve order by expressions against them first
            ImmutableMultimap.Builder<QualifiedName, Expression> assignments = ImmutableMultimap.builder();
            for (SelectItem item : node.getSelectItems()) {
                if (item instanceof SingleColumn) {
                    SingleColumn column = (SingleColumn) item;
                    Optional<Identifier> alias = column.getAlias();
                    if (alias.isPresent()) {
                        assignments.put(QualifiedName.of(alias.get().getValue()), column.getExpression()); // TODO: need to know if alias was quoted
                    }
                    else if (column.getExpression() instanceof Identifier) {
                        assignments.put(QualifiedName.of(((Identifier) column.getExpression()).getValue()), column.getExpression());
                    }
                }
            }

            return assignments.build();
        }

        private class OrderByExpressionRewriter
                extends ExpressionRewriter<Void>
        {
            private final Multimap<QualifiedName, Expression> assignments;

            public OrderByExpressionRewriter(Multimap<QualifiedName, Expression> assignments)
            {
                this.assignments = assignments;
            }

            @Override
            public Expression rewriteIdentifier(Identifier reference, Void context, ExpressionTreeRewriter<Void> treeRewriter)
            {
                // if this is a simple name reference, try to resolve against output columns
                QualifiedName name = QualifiedName.of(reference.getValue());
                Set<Expression> expressions = ImmutableSet.copyOf(assignments.get(name));

                if (expressions.size() > 1) {
                    throw new SemanticException(AMBIGUOUS_ATTRIBUTE, reference, "'%s' in ORDER BY is ambiguous", name);
                }

                if (expressions.size() == 1) {
                    return Iterables.getOnlyElement(expressions);
                }

                // otherwise, couldn't resolve name against output aliases, so fall through...
                return reference;
            }
        }

        private void checkGroupingSetsCount(GroupBy node)
        {
            // If groupBy is distinct then crossProduct will be overestimated if there are duplicate grouping sets.
            int crossProduct = 1;
            for (GroupingElement element : node.getGroupingElements()) {
                try {
                    int product;
                    if (element instanceof SimpleGroupBy) {
                        product = 1;
                    }
                    else if (element instanceof Cube) {
                        int exponent = element.getExpressions().size();
                        if (exponent > 30) {
                            throw new ArithmeticException();
                        }
                        product = 1 << exponent;
                    }
                    else if (element instanceof Rollup) {
                        product = element.getExpressions().size() + 1;
                    }
                    else if (element instanceof GroupingSets) {
                        product = ((GroupingSets) element).getSets().size();
                    }
                    else {
                        throw new UnsupportedOperationException("Unsupported grouping element type: " + element.getClass().getName());
                    }
                    crossProduct = Math.multiplyExact(crossProduct, product);
                }
                catch (ArithmeticException e) {
                    throw new SemanticException(TOO_MANY_GROUPING_SETS, node,
                            "GROUP BY has more than %s grouping sets but can contain at most %s", Integer.MAX_VALUE, getMaxGroupingSets(session));
                }
                if (crossProduct > getMaxGroupingSets(session)) {
                    throw new SemanticException(TOO_MANY_GROUPING_SETS, node,
                            "GROUP BY has %s grouping sets but can contain at most %s", crossProduct, getMaxGroupingSets(session));
                }
            }
        }

        private List<Expression> analyzeGroupBy(QuerySpecification node, Scope scope, List<Expression> outputExpressions)
        {
            if (node.getGroupBy().isPresent()) {
                ImmutableList.Builder<Set<FieldId>> cubes = ImmutableList.builder();
                ImmutableList.Builder<List<FieldId>> rollups = ImmutableList.builder();
                ImmutableList.Builder<List<Set<FieldId>>> sets = ImmutableList.builder();
                ImmutableList.Builder<Expression> complexExpressions = ImmutableList.builder();
                ImmutableList.Builder<Expression> groupingExpressions = ImmutableList.builder();

                checkGroupingSetsCount(node.getGroupBy().get());
                for (GroupingElement groupingElement : node.getGroupBy().get().getGroupingElements()) {
                    if (groupingElement instanceof SimpleGroupBy) {
                        for (Expression column : groupingElement.getExpressions()) {
                            Map<String, String> columnAliasMap = new HashMap<>();
                            List<SelectItem> selectItemList = node.getSelect().getSelectItems();
                            if (selectItemList.size() > 0) {
                                for (SelectItem si : selectItemList) {
                                    if (si instanceof SingleColumn) {
                                        Expression ex = ((SingleColumn) si).getExpression();
                                        if ((ex instanceof DereferenceExpression) && ((SingleColumn) si).getAlias().isPresent()) {
                                            columnAliasMap.put(((SingleColumn) si).getAlias().get().getValue(), ((DereferenceExpression) ex).getField().getValue());
                                        }
                                        if ((ex instanceof Identifier) && ((SingleColumn) si).getAlias().isPresent()) {
                                            columnAliasMap.put(((SingleColumn) si).getAlias().get().getValue(), ((Identifier) ex).getValue());
                                        }
                                    }
                                }
                            }
                            // simple GROUP BY expressions allow ordinals or arbitrary expressions
                            if (column instanceof LongLiteral) {
                                long ordinal = ((LongLiteral) column).getValue();
                                if (ordinal < 1 || ordinal > outputExpressions.size()) {
                                    throw new SemanticException(INVALID_ORDINAL, column, "GROUP BY position %s is not in select list", ordinal);
                                }

                                column = outputExpressions.get(toIntExact(ordinal - 1));
                            }
                            else {
                                if ((column instanceof Identifier) && columnAliasMap.containsKey(((Identifier) column).getValue())) {
                                    String columnName = columnAliasMap.get(((Identifier) column).getValue());
                                    column = new Identifier(((Identifier) column).getLocation().get(), columnName, ((Identifier) column).isDelimited());
                                }
                                analyzeExpression(column, scope);
                            }

                            FieldId field = analysis.getColumnReferenceFields().get(NodeRef.of(column));
                            if (field != null) {
                                sets.add(ImmutableList.of(ImmutableSet.of(field)));
                            }
                            else {
                                verifyNoAggregateWindowOrGroupingFunctions(metadata, column, "GROUP BY clause");
                                analysis.recordSubqueries(node, analyzeExpression(column, scope));
                                complexExpressions.add(column);
                            }

                            groupingExpressions.add(column);
                        }
                    }
                    else {
                        for (Expression column : groupingElement.getExpressions()) {
                            analyzeExpression(column, scope);
                            if (!analysis.getColumnReferences().contains(NodeRef.of(column))) {
                                throw new SemanticException(SemanticErrorCode.MUST_BE_COLUMN_REFERENCE, column, "GROUP BY expression must be a column reference: %s", column);
                            }

                            groupingExpressions.add(column);
                        }

                        if (groupingElement instanceof Cube) {
                            Set<FieldId> cube = groupingElement.getExpressions().stream()
                                    .map(NodeRef::of)
                                    .map(analysis.getColumnReferenceFields()::get)
                                    .collect(toImmutableSet());

                            cubes.add(cube);
                        }
                        else if (groupingElement instanceof Rollup) {
                            List<FieldId> rollup = groupingElement.getExpressions().stream()
                                    .map(NodeRef::of)
                                    .map(analysis.getColumnReferenceFields()::get)
                                    .collect(toImmutableList());

                            rollups.add(rollup);
                        }
                        else if (groupingElement instanceof GroupingSets) {
                            List<Set<FieldId>> groupingSets = ((GroupingSets) groupingElement).getSets().stream()
                                    .map(set -> set.stream()
                                            .map(NodeRef::of)
                                            .map(analysis.getColumnReferenceFields()::get)
                                            .collect(toImmutableSet()))
                                    .collect(toImmutableList());

                            sets.add(groupingSets);
                        }
                    }
                }

                List<Expression> expressions = groupingExpressions.build();
                for (Expression expression : expressions) {
                    Type type = analysis.getType(expression);
                    if (!type.isComparable()) {
                        throw new SemanticException(TYPE_MISMATCH, node, "%s is not comparable, and therefore cannot be used in GROUP BY", type);
                    }
                }

                analysis.setGroupByExpressions(node, expressions);
                analysis.setGroupingSets(node, new Analysis.GroupingSetAnalysis(cubes.build(), rollups.build(), sets.build(), complexExpressions.build()));

                return expressions;
            }

            if (hasAggregates(node) || node.getHaving().isPresent()) {
                analysis.setGroupByExpressions(node, ImmutableList.of());
            }

            return ImmutableList.of();
        }

        private Scope computeAndAssignOutputScope(QuerySpecification node, Optional<Scope> scope, Scope sourceScope)
        {
            ImmutableList.Builder<Field> outputFields = ImmutableList.builder();

            for (SelectItem item : node.getSelect().getSelectItems()) {
                if (item instanceof AllColumns) {
                    // expand * and T.*
                    Optional<QualifiedName> starPrefix = ((AllColumns) item).getPrefix();

                    for (Field field : sourceScope.getRelationType().resolveFieldsWithPrefix(starPrefix)) {
                        outputFields.add(Field.newUnqualified(field.getName(), field.getType(), field.getOriginTable(), field.getOriginColumnName(), false));
                    }
                }
                else if (item instanceof SingleColumn) {
                    SingleColumn column = (SingleColumn) item;

                    Expression expression = column.getExpression();
                    Optional<Identifier> field = column.getAlias();

                    Optional<QualifiedObjectName> originTable = Optional.empty();
                    Optional<String> originColumn = Optional.empty();
                    QualifiedName name = null;

                    if (expression instanceof Identifier) {
                        name = QualifiedName.of(((Identifier) expression).getValue());
                    }
                    else if (expression instanceof DereferenceExpression) {
                        name = DereferenceExpression.getQualifiedName((DereferenceExpression) expression);
                    }

                    if (name != null) {
                        List<Field> matchingFields = sourceScope.getRelationType().resolveFields(name);
                        if (!matchingFields.isEmpty()) {
                            originTable = matchingFields.get(0).getOriginTable();
                            originColumn = matchingFields.get(0).getOriginColumnName();
                        }
                    }

                    if (!field.isPresent()) {
                        if (name != null) {
                            field = Optional.of(getLast(name.getOriginalParts()));
                        }
                    }

                    outputFields.add(Field.newUnqualified(field.map(Identifier::getValue), analysis.getType(expression), originTable, originColumn, column.getAlias().isPresent())); // TODO don't use analysis as a side-channel. Use outputExpressions to look up the type
                }
                else {
                    throw new IllegalArgumentException("Unsupported SelectItem type: " + item.getClass().getName());
                }
            }

            return createAndAssignScope(node, scope, outputFields.build());
        }

        private Scope computeAndAssignOrderByScope(OrderBy node, Scope sourceScope, Scope outputScope)
        {
            // ORDER BY should "see" both output and FROM fields during initial analysis and non-aggregation query planning
            Scope orderByScope = Scope.builder()
                    .withParent(sourceScope)
                    .withRelationType(outputScope.getRelationId(), outputScope.getRelationType())
                    .build();
            analysis.setScope(node, orderByScope);
            return orderByScope;
        }

        private Scope computeAndAssignOrderByScopeWithAggregation(OrderBy node, Scope sourceScope, Scope outputScope, List<FunctionCall> aggregations, List<Expression> groupByExpressions, List<GroupingOperation> groupingOperations)
        {
            // This scope is only used for planning. When aggregation is present then
            // only output fields, groups and aggregation expressions should be visible from ORDER BY expression
            ImmutableList.Builder<Expression> orderByAggregationExpressionsBuilder = ImmutableList.<Expression>builder()
                    .addAll(groupByExpressions)
                    .addAll(aggregations)
                    .addAll(groupingOperations);

            // Don't add aggregate complex expressions that contains references to output column because the names would clash in TranslationMap during planning.
            List<Expression> orderByExpressionsReferencingOutputScope = AstUtils.preOrder(node)
                    .filter(Expression.class::isInstance)
                    .map(Expression.class::cast)
                    .filter(expression -> hasReferencesToScope(expression, analysis, outputScope))
                    .collect(toImmutableList());
            List<Expression> orderByAggregationExpressions = orderByAggregationExpressionsBuilder.build().stream()
                    .filter(expression -> !orderByExpressionsReferencingOutputScope.contains(expression) || analysis.isColumnReference(expression))
                    .collect(toImmutableList());

            // generate placeholder fields
            Set<Field> seen = new HashSet<>();
            List<Field> orderByAggregationSourceFields = orderByAggregationExpressions.stream()
                    .map(expression -> {
                        // generate qualified placeholder field for GROUP BY expressions that are column references
                        Optional<Field> sourceField = sourceScope.tryResolveField(expression)
                                .filter(resolvedField -> seen.add(resolvedField.getField()))
                                .map(ResolvedField::getField);
                        return sourceField
                                .orElse(Field.newUnqualified(Optional.empty(), analysis.getType(expression)));
                    })
                    .collect(toImmutableList());

            Scope orderByAggregationScope = Scope.builder()
                    .withRelationType(RelationId.anonymous(), new RelationType(orderByAggregationSourceFields))
                    .build();

            Scope orderByScope = Scope.builder()
                    .withParent(orderByAggregationScope)
                    .withRelationType(outputScope.getRelationId(), outputScope.getRelationType())
                    .build();
            analysis.setScope(node, orderByScope);
            analysis.setOrderByAggregates(node, orderByAggregationExpressions);
            return orderByScope;
        }

        private List<Expression> analyzeSelect(QuerySpecification node, Scope scope)
        {
            ImmutableList.Builder<Expression> outputExpressionBuilder = ImmutableList.builder();

            for (SelectItem item : node.getSelect().getSelectItems()) {
                if (item instanceof AllColumns) {
                    // expand * and T.*
                    Optional<QualifiedName> starPrefix = ((AllColumns) item).getPrefix();

                    RelationType relationType = scope.getRelationType();
                    List<Field> fields = relationType.resolveFieldsWithPrefix(starPrefix);
                    if (fields.isEmpty()) {
                        if (starPrefix.isPresent()) {
                            throw new SemanticException(MISSING_TABLE, item, "Table '%s' not found", starPrefix.get());
                        }
                        if (!node.getFrom().isPresent()) {
                            throw new SemanticException(WILDCARD_WITHOUT_FROM, item, "SELECT * not allowed in queries without FROM clause");
                        }
                        throw new SemanticException(COLUMN_NAME_NOT_SPECIFIED, item, "SELECT * not allowed from relation that has no columns");
                    }

                    for (Field field : fields) {
                        int fieldIndex = relationType.indexOf(field);
                        FieldReference expression = new FieldReference(fieldIndex);
                        outputExpressionBuilder.add(expression);
                        ExpressionAnalysis expressionAnalysis = analyzeExpression(expression, scope);

                        Type type = expressionAnalysis.getType(expression);
                        if (node.getSelect().isDistinct() && !type.isComparable()) {
                            throw new SemanticException(TYPE_MISMATCH, node.getSelect(), "DISTINCT can only be applied to comparable types (actual: %s)", type);
                        }
                    }
                }
                else if (item instanceof SingleColumn) {
                    SingleColumn column = (SingleColumn) item;
                    ExpressionAnalysis expressionAnalysis = analyzeExpression(column.getExpression(), scope);
                    analysis.recordSubqueries(node, expressionAnalysis);
                    outputExpressionBuilder.add(column.getExpression());

                    Type type = expressionAnalysis.getType(column.getExpression());
                    if (node.getSelect().isDistinct() && !type.isComparable()) {
                        throw new SemanticException(TYPE_MISMATCH, node.getSelect(), "DISTINCT can only be applied to comparable types (actual: %s): %s", type, column.getExpression());
                    }
                }
                else {
                    throw new IllegalArgumentException("Unsupported SelectItem type: " + item.getClass().getName());
                }
            }

            ImmutableList<Expression> result = outputExpressionBuilder.build();
            analysis.setOutputExpressions(node, result);

            return result;
        }

        public void analyzeWhere(Node node, Scope scope, Expression predicate)
        {
            verifyNoAggregateWindowOrGroupingFunctions(metadata, predicate, "WHERE clause");

            ExpressionAnalysis expressionAnalysis = analyzeExpression(predicate, scope);
            analysis.recordSubqueries(node, expressionAnalysis);

            Type predicateType = expressionAnalysis.getType(predicate);
            if (!predicateType.equals(BOOLEAN)) {
                if (!predicateType.equals(UNKNOWN)) {
                    throw new SemanticException(TYPE_MISMATCH, predicate, "WHERE clause must evaluate to a boolean: actual type %s", predicateType);
                }
                // coerce null to boolean
                analysis.addCoercion(predicate, BOOLEAN, false);
            }

            analysis.setWhere(node, predicate);
        }

        private Scope analyzeFrom(QuerySpecification node, Optional<Scope> scope)
        {
            if (node.getFrom().isPresent()) {
                return process(node.getFrom().get(), scope);
            }

            return createScope(scope);
        }

        private void analyzeGroupingOperations(QuerySpecification node, List<Expression> outputExpressions, List<Expression> orderByExpressions)
        {
            List<GroupingOperation> groupingOperations = extractExpressions(Iterables.concat(outputExpressions, orderByExpressions), GroupingOperation.class);
            boolean isGroupingOperationPresent = !groupingOperations.isEmpty();

            if (isGroupingOperationPresent && !node.getGroupBy().isPresent()) {
                throw new SemanticException(
                        INVALID_PROCEDURE_ARGUMENTS,
                        node,
                        "A GROUPING() operation can only be used with a corresponding GROUPING SET/CUBE/ROLLUP/GROUP BY clause");
            }

            analysis.setGroupingOperations(node, groupingOperations);
        }

        private void analyzeAggregations(
                QuerySpecification node,
                Scope sourceScope,
                Optional<Scope> orderByScope,
                List<Expression> groupByExpressions,
                List<Expression> outputExpressions,
                List<Expression> orderByExpressions)
        {
            checkState(orderByExpressions.isEmpty() || orderByScope.isPresent(), "non-empty orderByExpressions list without orderByScope provided");

            List<FunctionCall> aggregates = extractAggregateFunctions(Iterables.concat(outputExpressions, orderByExpressions), metadata);
            analysis.setAggregates(node, aggregates);

            if (analysis.isAggregation(node)) {
                // ensure SELECT, ORDER BY and HAVING are constant with respect to group
                // e.g, these are all valid expressions:
                //     SELECT f(a) GROUP BY a
                //     SELECT f(a + 1) GROUP BY a + 1
                //     SELECT a + sum(b) GROUP BY a
                List<Expression> distinctGroupingColumns = groupByExpressions.stream()
                        .distinct()
                        .collect(toImmutableList());

                for (Expression expression : outputExpressions) {
                    verifySourceAggregations(distinctGroupingColumns, sourceScope, expression, metadata, analysis);
                }

                for (Expression expression : orderByExpressions) {
                    verifyOrderByAggregations(distinctGroupingColumns, sourceScope, orderByScope.get(), expression, metadata, analysis);
                }
            }
        }

        private boolean hasAggregates(QuerySpecification node)
        {
            ImmutableList.Builder<Node> toExtractBuilder = ImmutableList.builder();

            toExtractBuilder.addAll(node.getSelect().getSelectItems().stream()
                    .collect(toImmutableList()));

            toExtractBuilder.addAll(getSortItemsFromOrderBy(node.getOrderBy()));

            List<FunctionCall> aggregates = extractAggregateFunctions(toExtractBuilder.build(), metadata);

            return !aggregates.isEmpty();
        }

        private RelationType analyzeView(Query query, QualifiedObjectName name, Optional<String> catalog, Optional<String> schema, Optional<String> owner, Table node)
        {
            try {
                // run view as view owner if set; otherwise, run as session user
                Identity identity;
                AccessControl viewAccessControl;
                if (owner.isPresent() && !owner.get().equals(session.getIdentity().getUser())) {
                    identity = new Identity(owner.get(), Optional.empty());
                    viewAccessControl = new ViewAccessControl(accessControl);
                }
                else {
                    identity = session.getIdentity();
                    viewAccessControl = accessControl;
                }

                // TODO: record path in view definition (?) (check spec) and feed it into the session object we use to evaluate the query defined by the view
                Session viewSession = createViewSession(catalog, schema, identity, session.getPath());

                StatementAnalyzer analyzer = new StatementAnalyzer(analysis, metadata, sqlParser, viewAccessControl, viewSession, warningCollector);
                Scope queryScope = analyzer.analyze(query, Scope.create());
                return queryScope.getRelationType().withAlias(name.getObjectName(), null);
            }
            catch (RuntimeException e) {
                throwIfInstanceOf(e, PrestoException.class);
                throw new SemanticException(VIEW_ANALYSIS_ERROR, node, "Failed analyzing stored view '%s': %s", name, e.getMessage());
            }
        }

        private Query parseView(String view, QualifiedObjectName name, Node node)
        {
            try {
                return (Query) sqlParser.createStatement(view, createParsingOptions(session));
            }
            catch (ParsingException e) {
                throw new SemanticException(VIEW_PARSE_ERROR, node, "Failed parsing stored view '%s': %s", name, e.getMessage());
            }
        }

        private boolean isViewStale(List<ViewColumn> columns, Collection<Field> fields, QualifiedObjectName name, Node node)
        {
            if (columns.size() != fields.size()) {
                return true;
            }

            List<Field> fieldList = ImmutableList.copyOf(fields);
            for (int i = 0; i < columns.size(); i++) {
                ViewColumn column = columns.get(i);
                Type type = getViewColumnType(column, name, node);
                Field field = fieldList.get(i);
                if (!column.getName().equalsIgnoreCase(field.getName().orElse(null)) ||
                        !typeCoercion.canCoerce(field.getType(), type)) {
                    return true;
                }
            }

            return false;
        }

        private Type getViewColumnType(ViewColumn column, QualifiedObjectName name, Node node)
        {
            try {
                return metadata.getType(column.getType());
            }
            catch (TypeNotFoundException e) {
                throw new SemanticException(VIEW_ANALYSIS_ERROR, node, "Unknown type '%s' for column '%s' in view: %s", column.getType(), column.getName(), name);
            }
        }

        private ExpressionAnalysis analyzeExpression(Expression expression, Scope scope)
        {
            return ExpressionAnalyzer.analyzeExpression(
                    session,
                    metadata,
                    accessControl,
                    sqlParser,
                    scope,
                    analysis,
                    expression,
                    WarningCollector.NOOP);
        }

        private void analyzeRowFilter(String currentIdentity, Table table, QualifiedObjectName name, Scope scope, ViewExpression filter)
        {
            if (analysis.hasRowFilter(name, currentIdentity)) {
                throw new PrestoException(INVALID_ROW_FILTER, extractLocation(table), format("Row filter for '%s' is recursive", name), null);
            }

            Expression expression;
            try {
                expression = sqlParser.createExpression(filter.getExpression(), createParsingOptions(session));
            }
            catch (ParsingException e) {
                throw new PrestoException(INVALID_ROW_FILTER, extractLocation(table), format("Invalid row filter for '%s': %s", name, e.getErrorMessage()), e);
            }

            analysis.registerTableForRowFiltering(name, currentIdentity);
            ExpressionAnalysis expressionAnalysis;
            try {
                expressionAnalysis = ExpressionAnalyzer.analyzeExpression(
                        createViewSession(filter.getCatalog(), filter.getSchema(), new Identity(filter.getIdentity(), Optional.empty()), session.getPath()), // TODO: path should be included in row filter
                        metadata,
                        accessControl,
                        sqlParser,
                        scope,
                        analysis,
                        expression,
                        warningCollector);
            }
            catch (PrestoException e) {
                throw new PrestoException(e::getErrorCode, extractLocation(table), format("Invalid row filter for '%s': %s", name, e.getMessage()), e);
            }
            finally {
                analysis.unregisterTableForRowFiltering(name, currentIdentity);
            }

            verifyNoAggregateWindowOrGroupingFunctions(metadata, expression, format("Row filter for '%s'", name));

            analysis.recordSubqueries(expression, expressionAnalysis);

            Type actualType = expressionAnalysis.getType(expression);
            if (!actualType.equals(BOOLEAN)) {
                TypeCoercion coercion = new TypeCoercion(metadata::getType);

                if (!coercion.canCoerce(actualType, BOOLEAN)) {
                    throw new PrestoException(StandardErrorCode.TYPE_MISMATCH, extractLocation(table), format("Expected row filter for '%s' to be of type BOOLEAN, but was %s", name, actualType), null);
                }

                analysis.addCoercion(expression, BOOLEAN, coercion.isTypeOnlyCoercion(actualType, BOOLEAN));
            }

            analysis.addRowFilter(table, expression);
        }

        private void analyzeColumnMask(String currentIdentity, Table table, QualifiedObjectName tableName, Field field, Scope scope, ViewExpression mask)
        {
            String column = field.getName().get();
            if (analysis.hasColumnMask(tableName, column, currentIdentity)) {
                throw new PrestoException(INVALID_COLUMN_MASK, extractLocation(table), format("Column mask for '%s.%s' is recursive", tableName, column), null);
            }

            Expression expression;
            try {
                expression = sqlParser.createExpression(mask.getExpression(), createParsingOptions(session));
            }
            catch (ParsingException e) {
                throw new PrestoException(INVALID_COLUMN_MASK, extractLocation(table), format("Invalid column mask for '%s.%s': %s", tableName, column, e.getErrorMessage()), e);
            }

            ExpressionAnalysis expressionAnalysis;
            analysis.registerTableForColumnMasking(tableName, column, currentIdentity);
            try {
                expressionAnalysis = ExpressionAnalyzer.analyzeExpression(
                        createViewSession(mask.getCatalog(), mask.getSchema(), new Identity(mask.getIdentity(), Optional.empty()), session.getPath()), // TODO: path should be included in row filter
                        metadata,
                        accessControl,
                        sqlParser,
                        scope,
                        analysis,
                        expression,
                        warningCollector);
            }
            catch (PrestoException e) {
                throw new PrestoException(e::getErrorCode, extractLocation(table), format("Invalid column mask for '%s.%s': %s", tableName, column, e.getRawMessage()), e);
            }
            finally {
                analysis.unregisterTableForColumnMasking(tableName, column, currentIdentity);
            }

            verifyNoAggregateWindowOrGroupingFunctions(metadata, expression, format("Column mask for '%s.%s'", table.getName(), column));

            analysis.recordSubqueries(expression, expressionAnalysis);

            Type expectedType = field.getType();
            Type actualType = expressionAnalysis.getType(expression);
            if (!actualType.equals(expectedType)) {
                TypeCoercion coercion = new TypeCoercion(metadata::getType);

                if (!coercion.canCoerce(actualType, field.getType())) {
                    throw new PrestoException(StandardErrorCode.TYPE_MISMATCH, extractLocation(table), format("Expected column mask for '%s.%s' to be of type %s, but was %s", tableName, column, field.getType(), actualType), null);
                }

                // TODO: this should be "coercion.isTypeOnlyCoercion(actualType, expectedType)", but type-only coercions are broken
                // due to the line "changeType(value, returnType)" in SqlToRowExpressionTranslator.visitCast. If there's an expression
                // like CAST(CAST(x AS VARCHAR(1)) AS VARCHAR(2)), it determines that the outer cast is type-only and converts the expression
                // to CAST(x AS VARCHAR(2)) by changing the type of the inner cast.
                analysis.addCoercion(expression, expectedType, false);
            }

            analysis.addColumnMask(table, column, expression);
        }

        private List<Expression> descriptorToFields(Scope scope)
        {
            ImmutableList.Builder<Expression> builder = ImmutableList.builder();
            for (int fieldIndex = 0; fieldIndex < scope.getRelationType().getAllFieldCount(); fieldIndex++) {
                FieldReference expression = new FieldReference(fieldIndex);
                builder.add(expression);
                analyzeExpression(expression, scope);
            }
            return builder.build();
        }

        private Scope analyzeWith(Query node, Optional<Scope> scope)
        {
            // analyze WITH clause
            if (!node.getWith().isPresent()) {
                return createScope(scope);
            }
            With with = node.getWith().get();
            if (with.isRecursive()) {
                throw new SemanticException(NOT_SUPPORTED, with, "Recursive WITH queries are not supported");
            }

            Scope.Builder withScopeBuilder = scopeBuilder(scope);
            for (WithQuery withQuery : with.getQueries()) {
                Query query = withQuery.getQuery();
                process(query, withScopeBuilder.build());

                String name = withQuery.getName().getValue().toLowerCase(ENGLISH);
                if (withScopeBuilder.containsNamedQuery(name)) {
                    throw new SemanticException(DUPLICATE_RELATION, withQuery, "WITH query name '%s' specified more than once", name);
                }

                // check if all or none of the columns are explicitly alias
                if (withQuery.getColumnNames().isPresent()) {
                    List<Identifier> columnNames = withQuery.getColumnNames().get();
                    RelationType queryDescriptor = analysis.getOutputDescriptor(query);
                    if (columnNames.size() != queryDescriptor.getVisibleFieldCount()) {
                        throw new SemanticException(MISMATCHED_COLUMN_ALIASES, withQuery, "WITH column alias list has %s entries but WITH query(%s) has %s columns", columnNames.size(), name, queryDescriptor.getVisibleFieldCount());
                    }
                }

                withScopeBuilder.withNamedQuery(name, withQuery);
            }

            Scope withScope = withScopeBuilder.build();
            analysis.setScope(with, withScope);
            return withScope;
        }

        private Session createViewSession(Optional<String> catalog, Optional<String> schema, Identity identity, SqlPath path)
        {
            return Session.builder(metadata.getSessionPropertyManager())
                    .setQueryId(session.getQueryId())
                    .setTransactionId(session.getTransactionId().orElse(null))
                    .setIdentity(identity)
                    .setSource(session.getSource().orElse(null))
                    .setCatalog(catalog.orElse(null))
                    .setSchema(schema.orElse(null))
                    .setPath(path)
                    .setTimeZoneKey(session.getTimeZoneKey())
                    .setLocale(session.getLocale())
                    .setRemoteUserAddress(session.getRemoteUserAddress().orElse(null))
                    .setUserAgent(session.getUserAgent().orElse(null))
                    .setClientInfo(session.getClientInfo().orElse(null))
                    .setStartTime(session.getStartTime())
                    .build();
        }

        private void verifySelectDistinct(QuerySpecification node, List<Expression> outputExpressions)
        {
            for (SortItem item : node.getOrderBy().get().getSortItems()) {
                Expression expression = item.getSortKey();

                if (expression instanceof LongLiteral) {
                    continue;
                }

                Expression rewrittenOrderByExpression = ExpressionTreeRewriter.rewriteWith(new OrderByExpressionRewriter(extractNamedOutputExpressions(node.getSelect())), expression);
                int index = outputExpressions.indexOf(rewrittenOrderByExpression);
                if (index == -1) {
                    throw new SemanticException(ORDER_BY_MUST_BE_IN_SELECT, node.getSelect(), "For SELECT DISTINCT, ORDER BY expressions must appear in select list");
                }

                if (!isDeterministic(expression)) {
                    throw new SemanticException(NONDETERMINISTIC_ORDER_BY_EXPRESSION_WITH_SELECT_DISTINCT, expression, "Non deterministic ORDER BY expression is not supported with SELECT DISTINCT");
                }
            }
        }

        private List<Expression> analyzeOrderBy(Node node, List<SortItem> sortItems, Scope orderByScope)
        {
            ImmutableList.Builder<Expression> orderByFieldsBuilder = ImmutableList.builder();

            for (SortItem item : sortItems) {
                Expression expression = item.getSortKey();

                if (expression instanceof LongLiteral) {
                    // this is an ordinal in the output tuple

                    long ordinal = ((LongLiteral) expression).getValue();
                    if (ordinal < 1 || ordinal > orderByScope.getRelationType().getVisibleFieldCount()) {
                        throw new SemanticException(INVALID_ORDINAL, expression, "ORDER BY position %s is not in select list", ordinal);
                    }

                    expression = new FieldReference(toIntExact(ordinal - 1));
                }

                ExpressionAnalysis expressionAnalysis = ExpressionAnalyzer.analyzeExpression(session,
                        metadata,
                        accessControl,
                        sqlParser,
                        orderByScope,
                        analysis,
                        expression,
                        WarningCollector.NOOP);
                analysis.recordSubqueries(node, expressionAnalysis);

                Type type = analysis.getType(expression);
                if (!type.isOrderable()) {
                    throw new SemanticException(TYPE_MISMATCH, node, "Type %s is not orderable, and therefore cannot be used in ORDER BY: %s", type, expression);
                }

                orderByFieldsBuilder.add(expression);
            }

            List<Expression> orderByFields = orderByFieldsBuilder.build();
            return orderByFields;
        }

        private void analyzeOffset(Offset node)
        {
            long rowCount;
            try {
                rowCount = Long.parseLong(node.getRowCount());
            }
            catch (NumberFormatException e) {
                throw new SemanticException(INVALID_OFFSET_ROW_COUNT, node, "Invalid OFFSET row count: %s", node.getRowCount());
            }
            if (rowCount < 0) {
                throw new SemanticException(INVALID_OFFSET_ROW_COUNT, node, "OFFSET row count must be greater or equal to 0 (actual value: %s)", rowCount);
            }
            analysis.setOffset(node, rowCount);
        }

        /**
         * @return true if the Query / QuerySpecification containing the analyzed
         * Limit or FetchFirst, must contain orderBy (i.e., for FetchFirst with ties).
         */
        private boolean analyzeLimit(Node node)
        {
            checkState(
                    node instanceof FetchFirst || node instanceof Limit,
                    "Invalid limit node type. Expected: FetchFirst or Limit. Actual: %s", node.getClass().getName());
            if (node instanceof FetchFirst) {
                return analyzeLimit((FetchFirst) node);
            }
            else {
                return analyzeLimit((Limit) node);
            }
        }

        private boolean analyzeLimit(FetchFirst node)
        {
            if (!node.getRowCount().isPresent()) {
                analysis.setLimit(node, 1);
            }
            else {
                long rowCount;
                try {
                    rowCount = Long.parseLong(node.getRowCount().get());
                }
                catch (NumberFormatException e) {
                    throw new SemanticException(INVALID_FETCH_FIRST_ROW_COUNT, node, "Invalid FETCH FIRST row count: %s", node.getRowCount().get());
                }
                if (rowCount <= 0) {
                    throw new SemanticException(INVALID_FETCH_FIRST_ROW_COUNT, node, "FETCH FIRST row count must be positive (actual value: %s)", rowCount);
                }
                analysis.setLimit(node, rowCount);
            }

            return node.isWithTies();
        }

        private boolean analyzeLimit(Limit node)
        {
            if (node.getLimit().equalsIgnoreCase("all")) {
                analysis.setLimit(node, OptionalLong.empty());
            }
            else {
                long rowCount;
                try {
                    rowCount = Long.parseLong(node.getLimit());
                }
                catch (NumberFormatException e) {
                    throw new SemanticException(INVALID_LIMIT_ROW_COUNT, node, "Invalid LIMIT row count: %s", node.getLimit());
                }
                if (rowCount < 0) {
                    throw new SemanticException(INVALID_LIMIT_ROW_COUNT, node, "LIMIT row count must be greater or equal to 0 (actual value: %s)", rowCount);
                }
                analysis.setLimit(node, rowCount);
            }

            return false;
        }

        private Scope createAndAssignScope(Node node, Optional<Scope> parentScope)
        {
            return createAndAssignScope(node, parentScope, emptyList());
        }

        private Scope createAndAssignScope(Node node, Optional<Scope> parentScope, Field... fields)
        {
            return createAndAssignScope(node, parentScope, new RelationType(fields));
        }

        private Scope createAndAssignScope(Node node, Optional<Scope> parentScope, List<Field> fields)
        {
            return createAndAssignScope(node, parentScope, new RelationType(fields));
        }

        private Scope createAndAssignScope(Node node, Optional<Scope> parentScope, RelationType relationType)
        {
            Scope scope = scopeBuilder(parentScope)
                    .withRelationType(RelationId.of(node), relationType)
                    .build();

            analysis.setScope(node, scope);
            return scope;
        }

        private Scope createScope(Optional<Scope> parentScope)
        {
            return scopeBuilder(parentScope).build();
        }

        private Scope.Builder scopeBuilder(Optional<Scope> parentScope)
        {
            Scope.Builder scopeBuilder = Scope.builder();

            if (parentScope.isPresent()) {
                // parent scope represents local query scope hierarchy. Local query scope
                // hierarchy should have outer query scope as ancestor already.
                scopeBuilder.withParent(parentScope.get());
            }
            else if (outerQueryScope.isPresent()) {
                scopeBuilder.withOuterQueryParent(outerQueryScope.get());
            }

            return scopeBuilder;
        }
    }

    private static boolean hasScopeAsLocalParent(Scope root, Scope parent)
    {
        Scope scope = root;
        while (scope.getLocalParent().isPresent()) {
            scope = scope.getLocalParent().get();
            if (scope.equals(parent)) {
                return true;
            }
        }

        return false;
    }

    private void validateCreateIndex(Table table, Optional<Scope> scope)
    {
        CreateIndex createIndex = (CreateIndex) analysis.getOriginalStatement();
        QualifiedObjectName tableFullName = createQualifiedObjectName(session, createIndex, createIndex.getTableName());
        accessControl.checkCanCreateIndex(session.getRequiredTransactionId(), session.getIdentity(), tableFullName);
        String tableName = tableFullName.toString();
        // check whether catalog support create index
        if (!metadata.isHeuristicIndexSupported(session, tableFullName)) {
            throw new SemanticException(NOT_SUPPORTED, createIndex,
                    "CREATE INDEX is not supported in catalog '%s'",
                    tableFullName.getCatalogName());
        }

        List<String> partitions = new ArrayList<>();
        String partitionColumn = null;
        if (createIndex.getExpression().isPresent()) {
            partitions = HeuristicIndexUtils.extractPartitions(createIndex.getExpression().get());
            // check partition name validate, create index …… where pt_d = xxx;
            // pt_d must be partition column
            Set<String> partitionColumns = partitions.stream().map(k -> k.substring(0, k.indexOf("="))).collect(Collectors.toSet());
            if (partitionColumns.size() > 1) {
                // currently only support one partition column
                throw new IllegalArgumentException("Heuristic index only supports predicates on one column");
            }
            // The only entry in set should be the only partition column name
            partitionColumn = partitionColumns.iterator().next();
        }

        Optional<TableHandle> tableHandle = metadata.getTableHandle(session, tableFullName);
        if (tableHandle.isPresent()) {
            if (!tableHandle.get().getConnectorHandle().isHeuristicIndexSupported()) {
                throw new SemanticException(NOT_SUPPORTED, table, "Catalog supported, but table storage format is not supported by heuristic index");
            }
            TableMetadata tableMetadata = metadata.getTableMetadata(session, tableHandle.get());
            List<String> availableColumns = tableMetadata.getColumns().stream().map(ColumnMetadata::getName).collect(Collectors.toList());
            for (Identifier column : createIndex.getColumnAliases()) {
                if (!availableColumns.contains(column.getValue().toLowerCase(Locale.ROOT))) {
                    throw new SemanticException(MISSING_ATTRIBUTE, table, "Column '%s' cannot be resolved", column.getValue());
                }
            }

            if (partitionColumn != null && !tableHandle.get().getConnectorHandle().isPartitionColumn(partitionColumn)) {
                throw new SemanticException(NOT_SUPPORTED, table, "Heuristic index creation is only supported for predicates on partition columns");
            }
        }
        else {
            throw new SemanticException(MISSING_ATTRIBUTE, table, "Table '%s' is invalid", tableFullName);
        }

        List<Pair<String, Type>> indexColumns = new LinkedList<>();
        for (Identifier i : createIndex.getColumnAliases()) {
            indexColumns.add(new Pair<>(i.toString(), UNKNOWN));
        }

        // For now, creating index for multiple columns is not supported
        if (indexColumns.size() > 1) {
            throw new SemanticException(NOT_SUPPORTED, table, "Multi-column indexes are currently not supported");
        }

        try {
            // Use this place holder to check the existence of index and lock the place
            Properties properties = new Properties();
            properties.setProperty(INPROGRESS_PROPERTY_KEY, "TRUE");
            CreateIndexMetadata placeHolder = new CreateIndexMetadata(
                    createIndex.getIndexName().toString(),
                    tableName,
                    createIndex.getIndexType(),
                    0L,
                    indexColumns,
                    partitions,
                    properties,
                    session.getUser(),
                    UNDEFINED);

            synchronized (StatementAnalyzer.class) {
                IndexClient.RecordStatus recordStatus = heuristicIndexerManager.getIndexClient().lookUpIndexRecord(placeHolder);
                switch (recordStatus) {
                    case SAME_NAME:
                        throw new SemanticException(INDEX_ALREADY_EXISTS, createIndex,
                                "Index '%s' already exists", createIndex.getIndexName().toString());
                    case SAME_CONTENT:
                        throw new SemanticException(INDEX_ALREADY_EXISTS, createIndex,
                                "Index with same (table,column,indexType) already exists");
                    case SAME_INDEX_PART_CONFLICT:
                        throw new SemanticException(INDEX_ALREADY_EXISTS, createIndex,
                                "Index with same (table,column,indexType) already exists and partition(s) contain conflicts");
                    case IN_PROGRESS_SAME_NAME:
                        throw new SemanticException(INDEX_ALREADY_EXISTS, createIndex,
                                "Index '%s' is being created by another user. Check running queries for details. If there is no running query for this index, " +
                                        "the index may be in an unexpected error state and should be dropped using 'DROP INDEX %s'",
                                createIndex.getIndexName().toString(), createIndex.getIndexName().toString());
                    case IN_PROGRESS_SAME_CONTENT:
                        throw new SemanticException(INDEX_ALREADY_EXISTS, createIndex,
                                "Index with same (table,column,indexType) is being created by another user. Check running queries for details. " +
                                        "If there is no running query for this index, the index may be in an unexpected error state and should be dropped using 'DROP INDEX'");
                    case IN_PROGRESS_SAME_INDEX_PART_CONFLICT:
                        if (partitions.isEmpty()) {
                            throw new SemanticException(INDEX_ALREADY_EXISTS, createIndex,
                                    "Index with same (table,column,indexType) is being created by another user. Check running queries for details. " +
                                            "If there is no running query for this index, the index may be in an unexpected error state and should be dropped using 'DROP INDEX %s'",
                                    createIndex.getIndexName().toString());
                        }
                        // allow different queries to run with explicitly same partitions
                    case SAME_INDEX_PART_CAN_MERGE:
                    case IN_PROGRESS_SAME_INDEX_PART_CAN_MERGE:
                        break;
                    case NOT_FOUND:
                        heuristicIndexerManager.getIndexClient().addIndexRecord(placeHolder);
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void validateUpdateIndex(Table table, Optional<Scope> scope)
    {
        UpdateIndex updateIndex = (UpdateIndex) analysis.getOriginalStatement();
        IndexRecord indexRecord;
        try {
            indexRecord = heuristicIndexerManager.getIndexClient().lookUpIndexRecord(updateIndex.getIndexName().toString());
        }
        catch (IOException e) {
            throw new UncheckedIOException("Error reading index records, ", e);
        }

        QualifiedObjectName tableFullName = QualifiedObjectName.valueOf(indexRecord.qualifiedTable);
        accessControl.checkCanCreateIndex(session.getRequiredTransactionId(), session.getIdentity(), tableFullName);
        String tableName = tableFullName.toString();

        Optional<TableHandle> tableHandle = metadata.getTableHandle(session, tableFullName);
        if (!tableHandle.isPresent()) {
            throw new SemanticException(MISSING_ATTRIBUTE, table, "Unable to update index. " +
                    "Index table '%s' may have been dropped from outside OLK. Index should also be dropped.", tableFullName);
        }

        List<Pair<String, Type>> indexColumns = new LinkedList<>();
        for (String i : indexRecord.columns) {
            indexColumns.add(new Pair<>(i, UNKNOWN));
        }

        try {
            // Use this place holder to check the existence of index and lock the place
            Properties properties = new Properties();
            properties.setProperty(INPROGRESS_PROPERTY_KEY, "TRUE");
            CreateIndexMetadata placeHolder = new CreateIndexMetadata(
                    updateIndex.getIndexName().toString(),
                    tableName,
                    indexRecord.indexType,
                    0L,
                    indexColumns,
                    indexRecord.partitions,
                    properties,
                    session.getUser(),
                    UNDEFINED);

            synchronized (StatementAnalyzer.class) {
                IndexClient.RecordStatus recordStatus = heuristicIndexerManager.getIndexClient().lookUpIndexRecord(placeHolder);
                switch (recordStatus) {
                    case IN_PROGRESS_SAME_NAME:
                        throw new SemanticException(INDEX_ALREADY_EXISTS, updateIndex,
                                "Index '%s' is being created by another user. Check running queries for details. If there is no running query for this index, " +
                                        "the index may be in an unexpected error state and should be dropped using 'DROP INDEX %s'",
                                updateIndex.getIndexName().toString(), updateIndex.getIndexName().toString());
                    case IN_PROGRESS_SAME_CONTENT:
                        throw new SemanticException(INDEX_ALREADY_EXISTS, updateIndex,
                                "Index with same (table,column,indexType) is being created by another user. Check running queries for details. " +
                                        "If there is no running query for this index, the index may be in an unexpected error state and should be dropped using 'DROP INDEX'");
                    case IN_PROGRESS_SAME_INDEX_PART_CONFLICT:
                        if (indexRecord.partitions.isEmpty()) {
                            throw new SemanticException(INDEX_ALREADY_EXISTS, updateIndex,
                                    "Index with same (table,column,indexType) is being created by another user. Check running queries for details. " +
                                            "If there is no running query for this index, the index may be in an unexpected error state and should be dropped using 'DROP INDEX %s'",
                                    updateIndex.getIndexName().toString());
                        }
                        // allow different queries to run with explicitly same partitions
                    case NOT_FOUND:
                        throw new SemanticException(MISSING_INDEX, updateIndex, "Index with name '%s' does not exist", updateIndex.getIndexName().toString());
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
