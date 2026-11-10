package com.getjobs.application.controller;

import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.DeliveryFilterService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeliveryFilterControllerTest {
    @Test
    void readsOnlyFilterSettingsAndValidatesCompleteWritesAndSaveFailure() throws Exception {
        ConfigService config = mock(ConfigService.class);
        when(config.getAllConfigsAsMap()).thenReturn(Map.of("API_KEY", "private-value"));
        var mvc = MockMvcBuilders.standaloneSetup(new DeliveryFilterController(new DeliveryFilterService(config))).build();
        mvc.perform(get("/api/delivery-filter")).andExpect(status().isOk())
                .andExpect(jsonPath("$.titleIncludes").value(""))
                .andExpect(jsonPath("$.contentExcludes").value(""))
                .andExpect(jsonPath("$.API_KEY").doesNotExist());
        mvc.perform(put("/api/delivery-filter").contentType(MediaType.APPLICATION_JSON).content("{\"titleIncludes\":\"Java\"}"))
                .andExpect(status().isBadRequest());
        verify(config, never()).batchUpdateConfigs(anyMap());

        String payload = "{\"titleIncludes\":\" Java \",\"titleExcludes\":\"外包\",\"contentIncludes\":\"Spring\",\"contentExcludes\":\"\"}";
        when(config.batchUpdateConfigs(anyMap())).thenReturn(4);
        mvc.perform(put("/api/delivery-filter").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(config).batchUpdateConfigs(Map.of("DELIVERY_FILTER_TITLE_INCLUDE", "Java", "DELIVERY_FILTER_TITLE_EXCLUDE", "外包",
                "DELIVERY_FILTER_CONTENT_INCLUDE", "Spring", "DELIVERY_FILTER_CONTENT_EXCLUDE", ""));
        when(config.batchUpdateConfigs(anyMap())).thenReturn(2);
        mvc.perform(put("/api/delivery-filter").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.success").value(false));
    }
}
