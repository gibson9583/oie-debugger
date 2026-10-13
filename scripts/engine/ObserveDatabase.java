import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read the isolated Derby database only after all engine processes have exited. */
class ObserveDatabase {
    public static void main(String[] args) throws Exception {
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + args[0])) {
            List<String> tables = new ArrayList<>();
            try (ResultSet metadata = connection.getMetaData().getTables(null, "APP", "D_%", new String[]{"TABLE"})) {
                while (metadata.next()) {
                    String name = metadata.getString("TABLE_NAME");
                    if (name.matches("D_(M|MM|MC)[0-9]+")) tables.add(name);
                }
            }
            Collections.sort(tables);
            tables.add(0, "D_CHANNELS");
            tables.add("EVENT");
            for (String table : tables) {
                System.out.println("TABLE " + table);
                try (Statement statement = connection.createStatement();
                        ResultSet rows = statement.executeQuery("SELECT * FROM " + table)) {
                    ResultSetMetaData columns = rows.getMetaData();
                    while (rows.next()) {
                        for (int i = 1; i <= columns.getColumnCount(); i++)
                            System.out.println(columns.getColumnName(i) + "=" + rows.getString(i));
                        System.out.println("END ROW");
                    }
                } catch (SQLException absent) {
                    if (!"42X05".equals(absent.getSQLState())) throw absent;
                    System.out.println("TABLE NOT CREATED " + table);
                }
            }
        }
        try { DriverManager.getConnection("jdbc:derby:;shutdown=true"); }
        catch (SQLException expected) {
            if (!"XJ015".equals(expected.getSQLState())) throw expected;
        }
    }
}
