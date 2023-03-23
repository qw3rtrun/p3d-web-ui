package org.qw3rtrun.p3d.ui;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.ui.view.Model;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebUiApplicationTests {

    @Autowired
    TestRestTemplate testRestTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void readTh1() {
        var heater = testRestTemplate.getForEntity("/printer/th1", Model.Heater.class).getBody();
        assertThat(heater).isNotNull()
                .satisfies(h -> assertThat(h.th1()).isPositive());
    }

    @Test
    void setTh1() {
        var request = new Model.Heater(0, true, 150);
        var heater = testRestTemplate.postForEntity("/printer/th1", request, Model.Heater.class).getBody();
        assertThat(heater).isNotNull()
                .satisfies(h -> assertThat(h.heating()).isTrue())
                .satisfies(h -> assertThat(h.target()).isEqualTo(150));
    }
}
