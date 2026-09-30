package com.agenthubfusion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FusionHealthControllerTest {
    private final JdbcTemplate database=mock(JdbcTemplate.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new FusionHealthController(database))
                .addFilters(new FusionSecurity("012345678901234567890123456789012345"))
                .build();
    }

    @Test void probesAreAvailableWithoutExposingManagementCommands() throws Exception {
        mvc.perform(get("/internal/fusion/health/ready")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/internal/fusion/health/live")).andExpect(status().isOk());
        mvc.perform(post("/internal/fusion/command").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/fusion/health/ready")).andExpect(status().isUnauthorized());
    }

    @Test void databaseOutageFailsReadinessWithoutRestartingAHealthyProcess() throws Exception {
        when(database.queryForObject(anyString(),eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("private database details"));
        mvc.perform(get("/internal/fusion/health/ready"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"status\":\"DOWN\"}"));
        mvc.perform(get("/internal/fusion/health/live")).andExpect(status().isOk());
    }
}
