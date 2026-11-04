package lab.codeql;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductControllerTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void findsProductByExactName() throws Exception {
        mvc.perform(get("/products").param("name", "Notebook"))
            .andExpect(status().isOk())
            .andExpect(content().json("[\"Notebook\"]"));
    }

    @Test
    void handlesSqlMetacharactersInName() throws Exception {
        mvc.perform(get("/products").param("name", "' OR '1'='1"))
            .andExpect(status().isOk())
            .andExpect(content().json("[\"Notebook\",\"Pencil\"]"));
    }
}
