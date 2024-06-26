package de.numcodex.feasibility_gui_backend.query.templates;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.numcodex.feasibility_gui_backend.query.dispatch.QueryHashCalculator;
import de.numcodex.feasibility_gui_backend.query.persistence.QueryContentRepository;
import de.numcodex.feasibility_gui_backend.query.persistence.QueryRepository;
import de.numcodex.feasibility_gui_backend.query.persistence.QueryTemplateRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueryTemplateSpringConfig {

  @Bean
  public QueryTemplateHandler createQueryTemplateHandler(
      QueryHashCalculator queryHashCalculator,
      @Qualifier("translation") ObjectMapper jsonUtil,
      QueryRepository queryRepository,
      QueryContentRepository queryContentRepository,
      QueryTemplateRepository queryTemplateRepository) {
    return new QueryTemplateHandler(queryHashCalculator, jsonUtil,
        queryRepository, queryContentRepository, queryTemplateRepository);
  }
}
