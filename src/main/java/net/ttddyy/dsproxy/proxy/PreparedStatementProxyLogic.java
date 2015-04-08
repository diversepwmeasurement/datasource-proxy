package net.ttddyy.dsproxy.proxy;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.StatementType;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.transform.ParameterReplacer;
import net.ttddyy.dsproxy.transform.ParameterTransformer;
import net.ttddyy.dsproxy.transform.TransformInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared logic for {@link PreparedStatement} and {@link CallableStatement} invocation.
 *
 * @author Tadaya Tsuyukubo
 * @since 1.2
 */
public class PreparedStatementProxyLogic {

    private PreparedStatement ps;
    private String query;
    private String dataSourceName;
    private Map<Object, ParameterSetOperation> parameters = new LinkedHashMap<Object, ParameterSetOperation>();
    private InterceptorHolder interceptorHolder;
    private JdbcProxyFactory jdbcProxyFactory = JdbcProxyFactory.DEFAULT;
    private StatementType statementType = StatementType.PREPARED;

    private List<Map<Object, ParameterSetOperation>> batchParameters = new ArrayList<Map<Object, ParameterSetOperation>>();

    public PreparedStatementProxyLogic() {
    }

    public PreparedStatementProxyLogic(PreparedStatement ps, String query, InterceptorHolder interceptorHolder, String dataSourceName, JdbcProxyFactory jdbcProxyFactory) {
        this.ps = ps;
        this.query = query;
        this.interceptorHolder = interceptorHolder;
        this.dataSourceName = dataSourceName;
        this.jdbcProxyFactory = jdbcProxyFactory;
        if (ps instanceof CallableStatement) {
            this.statementType = StatementType.CALLABLE;
        }
    }

    public Object invoke(Method method, Object[] args) throws Throwable {

        final String methodName = method.getName();

        if (!StatementMethodNames.METHODS_TO_INTERCEPT.contains(methodName)) {
            return MethodUtils.proceedExecution(method, ps, args);
        }

        // special treat for toString method
        if ("toString".equals(methodName)) {
            final StringBuilder sb = new StringBuilder();
            sb.append(ps.getClass().getSimpleName());   // PreparedStatement or CallableStatement
            sb.append(" [");
            sb.append(ps.toString());
            sb.append("]");
            return sb.toString(); // differentiate toString message.
        } else if ("getDataSourceName".equals(methodName)) {
            return dataSourceName;
        } else if ("getTarget".equals(methodName)) {
            // ProxyJdbcObject interface has a method to return original object.
            return ps;
        }

        if (StatementMethodNames.JDBC4_METHODS.contains(methodName)) {
            final Class<?> clazz = (Class<?>) args[0];
            if ("unwrap".equals(methodName)) {
                return ps.unwrap(clazz);
            } else if ("isWrapperFor".equals(methodName)) {
                return ps.isWrapperFor(clazz);
            }
        }

        if (StatementMethodNames.GET_CONNECTION_METHOD.contains(methodName)) {
            final Connection conn = (Connection) MethodUtils.proceedExecution(method, ps, args);
            return jdbcProxyFactory.createConnection(conn, interceptorHolder, dataSourceName);
        }


        if (StatementMethodNames.METHODS_TO_OPERATE_PARAMETER.contains(methodName)) {

            // for parameter operation method
            if (StatementMethodNames.PARAMETER_METHODS.contains(methodName)) {

                // operation to set or clear parameterOperationHolder
                if ("clearParameters".equals(methodName)) {
                    parameters.clear();
                } else {
                    parameters.put(args[0], new ParameterSetOperation(method, args));
                }

            } else if (StatementMethodNames.BATCH_PARAM_METHODS.contains(methodName)) {

                // Batch parameter operation
                if ("addBatch".equals(methodName)) {

                    // TODO: check
                    transformParameters(true, batchParameters.size());

                    // copy values
                    Map<Object, ParameterSetOperation> newParams = new LinkedHashMap<Object, ParameterSetOperation>(parameters);
                    batchParameters.add(newParams);

                    parameters.clear();
                } else if ("clearBatch".equals(methodName)) {
                    batchParameters.clear();
                }
            }

            // proceed execution, no need to call listener
            return MethodUtils.proceedExecution(method, ps, args);
        }


        // query execution methods

        final List<QueryInfo> queries = new ArrayList<QueryInfo>();
        boolean isBatchExecution = false;
        int batchSize = 0;

        if (StatementMethodNames.BATCH_EXEC_METHODS.contains(methodName)) {

            // one query with multiple parameters
            QueryInfo queryInfo = new QueryInfo(this.query);
            for (Map<Object, ParameterSetOperation> params : batchParameters) {
                queryInfo.getQueryArgsList().add(getQueryParameters(params));
            }
            queries.add(queryInfo);

            batchSize = batchParameters.size();
            batchParameters.clear();
            isBatchExecution = true;

        } else if (StatementMethodNames.QUERY_EXEC_METHODS.contains(methodName)) {

            transformParameters(false, 0);

            queries.add(new QueryInfo(query, getQueryParameters(parameters)));
        }

        final QueryExecutionListener listener = interceptorHolder.getListener();
        listener.beforeQuery(new ExecutionInfo(dataSourceName, this.statementType, isBatchExecution, batchSize, method, args), queries);

        // Invoke method on original Statement.
        final ExecutionInfo execInfo = new ExecutionInfo(dataSourceName, this.statementType, isBatchExecution, batchSize, method, args);

        try {
            final long beforeTime = System.currentTimeMillis();

            Object retVal = method.invoke(ps, args);

            final long afterTime = System.currentTimeMillis();

            execInfo.setResult(retVal);
            execInfo.setElapsedTime(afterTime - beforeTime);
            execInfo.setSuccess(true);

            return retVal;
        } catch (InvocationTargetException ex) {
            execInfo.setThrowable(ex.getTargetException());
            execInfo.setSuccess(false);
            throw ex.getTargetException();
        } finally {
            listener.afterQuery(execInfo, queries);
        }
    }

    private Map<String, Object> getQueryParameters(Map<Object, ParameterSetOperation> params) {
        Map<String, Object> queryParameters = new LinkedHashMap<String, Object>(params.size());
        for (ParameterSetOperation parameterSetOperation : params.values()) {
            Object[] args = parameterSetOperation.getArgs();

            Object key = args[0];  // key is either int(parameterIndex) or string(parameterName)
            String keyAsString = key instanceof String ? (String) key : key.toString();
            queryParameters.put(keyAsString, args[1]);  // all set operation has index 0=key, 1=value
        }
        return queryParameters;
    }

    private void transformParameters(boolean isBatch, int count) throws SQLException, IllegalAccessException, InvocationTargetException {

        // transform parameters
        final ParameterReplacer parameterReplacer = new ParameterReplacer(this.parameters);
        final TransformInfo transformInfo = new TransformInfo(ps.getClass(), dataSourceName, query, isBatch, count);
        final ParameterTransformer parameterTransformer = interceptorHolder.getParameterTransformer();
        parameterTransformer.transformParameters(parameterReplacer, transformInfo);

        if (parameterReplacer.isModified()) {

            ps.clearParameters();  // clear existing parameters

            // re-set parameters
            Map<Object, ParameterSetOperation> modifiedParameters = parameterReplacer.getModifiedParameters();
            for (ParameterSetOperation operation : modifiedParameters.values()) {
                final Method paramMethod = operation.getMethod();
                final Object[] paramArgs = operation.getArgs();
                paramMethod.invoke(ps, paramArgs);
            }

            // replace
            this.parameters = modifiedParameters;
        }
    }

}
