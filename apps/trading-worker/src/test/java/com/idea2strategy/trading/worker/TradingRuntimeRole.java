package com.idea2strategy.trading.worker;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A login that holds nothing but {@code idea2strategy_trading}, the way deployment wires the trading
 * runtime.
 *
 * <p>The Testcontainers user owns every table, so a test that runs the worker as that user proves
 * nothing about the generated grants. Running the worker's SQL through this login instead fails the
 * test with {@code permission denied} the moment the trading role is granted less than it executes.
 */
public final class TradingRuntimeRole {
    public static final String LOGIN = "trading_runtime_probe";
    public static final String PASSWORD = "trading-runtime-probe";

    private TradingRuntimeRole() {}

    /** Installs the login through {@code admin} and returns a data source that connects as it. */
    public static DriverManagerDataSource dataSource(String jdbcUrl, DataSource admin) {
        install(admin);
        DriverManagerDataSource runtime = new DriverManagerDataSource(jdbcUrl, LOGIN, PASSWORD);
        try (Connection connection = runtime.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select role.rolsuper or role.rolbypassrls,
                               (select array_agg(granted.rolname::text order by granted.rolname)
                                  from pg_auth_members membership
                                  join pg_roles granted on granted.oid = membership.roleid
                                 where membership.member = role.oid)
                          from pg_roles role
                         where role.rolname = current_user
                        """)) {
            result.next();
            if (result.getBoolean(1)) {
                throw new IllegalStateException(LOGIN + " must not bypass privilege checks");
            }
            String memberships = result.getString(2);
            if (!"{idea2strategy_trading}".equals(memberships)) {
                throw new IllegalStateException(
                        LOGIN + " must hold only idea2strategy_trading, but holds " + memberships);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return runtime;
    }

    /**
     * Bridges a gap outside the scope of these tests: the BEFORE INSERT trigger on
     * {@code trading.orders} calls {@code identity.require_active_account}, a SECURITY INVOKER
     * function that locks {@code identity.accounts} FOR SHARE, and the trading role holds nothing in
     * {@code identity}. Granted to the login directly, never to {@code idea2strategy_trading}, so
     * the generated grants under test stay exactly as the central bundle ships them.
     */
    public static void bridgeOrderCreationGuard(DataSource admin) {
        install(admin);
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("GRANT USAGE ON SCHEMA identity TO " + LOGIN);
            statement.execute("GRANT SELECT, UPDATE ON identity.accounts TO " + LOGIN);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Creates the login once per database and grants it the trading group role. */
    public static void install(DataSource admin) {
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    DO $$ BEGIN
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                        CREATE ROLE %s LOGIN INHERIT PASSWORD '%s';
                      END IF;
                    END $$
                    """.formatted(LOGIN, LOGIN, PASSWORD));
            statement.execute("GRANT idea2strategy_trading TO " + LOGIN);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
