package lab.codeql;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProductController {
    private final JdbcTemplate jdbc;

    ProductController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/products")
    List<String> findByName(@RequestParam("name") String name) {
        String sql = "SELECT name FROM products WHERE name = '" + name + "' ORDER BY name";
        return jdbc.queryForList(sql, String.class);
    }
}
