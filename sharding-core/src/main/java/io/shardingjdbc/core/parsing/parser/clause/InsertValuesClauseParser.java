/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
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
 * </p>
 */

package io.shardingjdbc.core.parsing.parser.clause;

import com.google.common.base.Optional;
import io.shardingjdbc.core.exception.ShardingJdbcException;
import io.shardingjdbc.core.metadata.ShardingMetaData;
import io.shardingjdbc.core.parsing.lexer.LexerEngine;
import io.shardingjdbc.core.parsing.lexer.token.DefaultKeyword;
import io.shardingjdbc.core.parsing.lexer.token.Keyword;
import io.shardingjdbc.core.parsing.lexer.token.Symbol;
import io.shardingjdbc.core.parsing.parser.clause.expression.BasicExpressionParser;
import io.shardingjdbc.core.parsing.parser.context.condition.AndCondition;
import io.shardingjdbc.core.parsing.parser.context.condition.Column;
import io.shardingjdbc.core.parsing.parser.context.condition.Condition;
import io.shardingjdbc.core.parsing.parser.context.condition.GeneratedKeyCondition;
import io.shardingjdbc.core.parsing.parser.context.insertvalue.InsertValue;
import io.shardingjdbc.core.parsing.parser.dialect.ExpressionParserFactory;
import io.shardingjdbc.core.parsing.parser.expression.SQLExpression;
import io.shardingjdbc.core.parsing.parser.expression.SQLNumberExpression;
import io.shardingjdbc.core.parsing.parser.expression.SQLPlaceholderExpression;
import io.shardingjdbc.core.parsing.parser.sql.dml.insert.InsertStatement;
import io.shardingjdbc.core.parsing.parser.token.InsertValuesToken;
import io.shardingjdbc.core.parsing.parser.token.ItemsToken;
import io.shardingjdbc.core.rule.ShardingRule;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * Insert values clause parser.
 *
 * @author zhangliang
 * @author maxiaoguang
 */
public class InsertValuesClauseParser implements SQLClauseParser {
    
    private final ShardingRule shardingRule;
    
    private final LexerEngine lexerEngine;
    
    private final BasicExpressionParser basicExpressionParser;
    
    public InsertValuesClauseParser(final ShardingRule shardingRule, final LexerEngine lexerEngine) {
        this.shardingRule = shardingRule;
        this.lexerEngine = lexerEngine;
        basicExpressionParser = ExpressionParserFactory.createBasicExpressionParser(lexerEngine);
    }
    
    /**
     * Parse insert values.
     *
     * @param insertStatement insert statement
     * @param shardingMetaData sharding meta data
     */
    public void parse(final InsertStatement insertStatement, final ShardingMetaData shardingMetaData) {
        Collection<Keyword> valueKeywords = new LinkedList<>();
        valueKeywords.add(DefaultKeyword.VALUES);
        valueKeywords.addAll(Arrays.asList(getSynonymousKeywordsForValues()));
        if (lexerEngine.skipIfEqual(valueKeywords.toArray(new Keyword[valueKeywords.size()]))) {
            parseValues(insertStatement);
        }
    }
    
    protected Keyword[] getSynonymousKeywordsForValues() {
        return new Keyword[0];
    }
    
    /**
     * Parse insert values.
     *
     * @param insertStatement insert statement
     */
    private void parseValues(final InsertStatement insertStatement) {
        int beginPosition = lexerEngine.getCurrentToken().getEndPosition() - lexerEngine.getCurrentToken().getLiterals().length();
        int endPosition;
        insertStatement.getSqlTokens().add(new InsertValuesToken(beginPosition, insertStatement.getTables().getSingleTableName()));
        do {
            beginPosition = lexerEngine.getCurrentToken().getEndPosition() - lexerEngine.getCurrentToken().getLiterals().length();
            lexerEngine.accept(Symbol.LEFT_PAREN);
            List<SQLExpression> sqlExpressions = new LinkedList<>();
            int columnsCount = 0;
            do {
                sqlExpressions.add(basicExpressionParser.parse(insertStatement));
                skipsDoubleColon();
                columnsCount++;
            } while (lexerEngine.skipIfEqual(Symbol.COMMA));
            removeGenerateKeyColumn(insertStatement, columnsCount);
            columnsCount = 0;
            int parametersCount = 0;
            AndCondition andCondition = new AndCondition();
            for (Column each : insertStatement.getColumns()) {
                SQLExpression sqlExpression = sqlExpressions.get(columnsCount);
                if (shardingRule.isShardingColumn(each)) {
                    andCondition.getConditions().add(new Condition(each, sqlExpression));
                }
                if (insertStatement.getGenerateKeyColumnIndex() == columnsCount) {
                    insertStatement.getGeneratedKeyConditions().add(createGeneratedKeyCondition(each, sqlExpression));
                }
                columnsCount++;
                if (sqlExpression instanceof SQLPlaceholderExpression) {
                    parametersCount++;
                }
            }
            lexerEngine.accept(Symbol.RIGHT_PAREN);
            endPosition = lexerEngine.getCurrentToken().getEndPosition() - lexerEngine.getCurrentToken().getLiterals().length();
            insertStatement.getInsertValues().getInsertValues().add(new InsertValue(lexerEngine.getInput().substring(beginPosition, endPosition), parametersCount));
            insertStatement.getConditions().getOrCondition().getAndConditions().add(andCondition);
        } while (lexerEngine.skipIfEqual(Symbol.COMMA));
        insertStatement.setInsertValuesListLastPosition(endPosition);
    }
    
    private void removeGenerateKeyColumn(final InsertStatement insertStatement, final int valueCount) {
        Optional<Column> generateKeyColumn = shardingRule.getGenerateKeyColumn(insertStatement.getTables().getSingleTableName());
        if (generateKeyColumn.isPresent() && valueCount < insertStatement.getColumns().size()) {
            List<ItemsToken> itemsTokens = insertStatement.getItemsTokens();
            insertStatement.getColumns().remove(new Column(generateKeyColumn.get().getName(), insertStatement.getTables().getSingleTableName()));
            for (ItemsToken each : itemsTokens) {
                each.getItems().remove(generateKeyColumn.get().getName());
                insertStatement.setGenerateKeyColumnIndex(-1);
            }
        }
    }
    
    private GeneratedKeyCondition createGeneratedKeyCondition(final Column column, final SQLExpression sqlExpression) {
        GeneratedKeyCondition result;
        if (sqlExpression instanceof SQLPlaceholderExpression) {
            result = new GeneratedKeyCondition(column, ((SQLPlaceholderExpression) sqlExpression).getIndex(), null);
        } else if (sqlExpression instanceof SQLNumberExpression) {
            result = new GeneratedKeyCondition(column, -1, ((SQLNumberExpression) sqlExpression).getNumber());
        } else {
            throw new ShardingJdbcException("Generated key only support number.");
        }
        return result;
    }
    
    private void skipsDoubleColon() {
        if (lexerEngine.skipIfEqual(Symbol.DOUBLE_COLON)) {
            lexerEngine.nextToken();
        }
    }
}
