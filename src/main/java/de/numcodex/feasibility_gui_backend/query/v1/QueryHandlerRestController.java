package de.numcodex.feasibility_gui_backend.query.v1;

import de.numcodex.feasibility_gui_backend.query.QueryHandlerService;
import de.numcodex.feasibility_gui_backend.query.QueryHandlerService.ResultDetail;
import de.numcodex.feasibility_gui_backend.query.QueryNotFoundException;
import de.numcodex.feasibility_gui_backend.query.api.StructuredQuery;
import de.numcodex.feasibility_gui_backend.query.persistence.UserBlacklist;
import de.numcodex.feasibility_gui_backend.query.persistence.UserBlacklistRepository;
import de.numcodex.feasibility_gui_backend.query.ratelimiting.AuthenticationHelper;
import de.numcodex.feasibility_gui_backend.query.ratelimiting.InvalidAuthenticationException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.ws.rs.core.Context;
import java.net.URI;
import java.security.Principal;

/*
Rest Interface for the UI to send queries from the ui to the ui backend.
*/
@RequestMapping("api/v1/query-handler")
@RestController("QueryHandlerRestController-v1")
@Slf4j
@CrossOrigin(origins = "${cors.allowedOrigins}", exposedHeaders = "Location")
public class QueryHandlerRestController {

  private final QueryHandlerService queryHandlerService;
  private final UserBlacklistRepository userBlacklistRepository;
  private final AuthenticationHelper authenticationHelper;
  private final String apiBaseUrl;

  @Value("${app.keycloakPowerRole}")
  private String keycloakPowerRole;

  @Value("${app.privacy.quota.soft.create.amount}")
  private int quotaSoftCreateAmount;

  @Value("${app.privacy.quota.soft.create.intervalMinutes}")
  private int quotaSoftCreateIntervalMinutes;

  @Value("${app.privacy.quota.hard.create.amount}")
  private int quotaHardCreateAmount;

  @Value("${app.privacy.quota.hard.create.intervalminutes}")
  private int quotaHardCreateIntervalMinutes;

  @Value("${PRIVACY_THRESHOLD_RESULTS:20}")
  private int privacyThresholdResults;

  public QueryHandlerRestController(QueryHandlerService queryHandlerService, UserBlacklistRepository userBlacklistRepository,
      AuthenticationHelper authenticationHelper, @Value("${app.apiBaseUrl}") String apiBaseUrl) {
    this.queryHandlerService = queryHandlerService;
    this.userBlacklistRepository = userBlacklistRepository;
    this.authenticationHelper = authenticationHelper;
    this.apiBaseUrl = apiBaseUrl;
  }

  @PostMapping(value = "run-query")
  @Deprecated
  public Mono<ResponseEntity<Object>> runQuery(@Valid @RequestBody StructuredQuery query,
      @Context HttpServletRequest request,
      Authentication authentication) throws InvalidAuthenticationException {

    String userId = authentication.getName();
    Optional<UserBlacklist> userBlacklistEntry = userBlacklistRepository.findByUserId(userId);
    boolean isPowerUser = authenticationHelper.hasAuthority(authentication, keycloakPowerRole);

    if (!isPowerUser && userBlacklistEntry.isPresent()) {
      return Mono.just(new ResponseEntity<>(HttpStatus.FORBIDDEN));
    }

    Long amountOfQueriesByUserAndHardInterval = queryHandlerService.getAmountOfQueriesByUserAndInterval(
        userId, quotaHardCreateIntervalMinutes);
    if (!isPowerUser && (quotaHardCreateAmount <= amountOfQueriesByUserAndHardInterval)) {
      log.info("User {} exceeded hard limit and is not a power user. Blacklisting...", userId);
      UserBlacklist userBlacklist = new UserBlacklist();
      userBlacklist.setUserId(userId);
      userBlacklistRepository.save(userBlacklist);
      return Mono.just(new ResponseEntity<>(HttpStatus.TOO_MANY_REQUESTS));
    }

    Long amountOfQueriesByUserAndSoftInterval = queryHandlerService.getAmountOfQueriesByUserAndInterval(
        userId, quotaSoftCreateIntervalMinutes);
    log.error("amount: " + amountOfQueriesByUserAndSoftInterval);
    if (quotaSoftCreateAmount <= amountOfQueriesByUserAndSoftInterval) {
      Long retryAfter = queryHandlerService.getRetryAfterTime(userId,
          quotaSoftCreateAmount - 1, quotaSoftCreateIntervalMinutes);
      log.error("retry after: " + retryAfter);
      HttpHeaders httpHeaders = new HttpHeaders();
      httpHeaders.add(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
      return Mono.just(new ResponseEntity<>(httpHeaders, HttpStatus.TOO_MANY_REQUESTS));
      //return new ResponseEntity<>(httpHeaders, HttpStatus.TOO_MANY_REQUESTS);
    }
    // Note: this is using a ResponseEntity instead of a ServerResponse since this is a
    //       @Controller annotated class. This can be adjusted as soon as we switch to the new
    //       functional web framework (if ever).
    return queryHandlerService.runQuery(query, userId)
        .map(queryId -> buildResultLocationUri(request, queryId))
        .map(resultLocation -> ResponseEntity.created(resultLocation).build())
        .onErrorResume(e -> {
          log.error("running a query for '%s' failed".formatted(userId), e);
          return Mono.just(ResponseEntity.internalServerError()
              .body(e.getMessage()));
        });
  }

  private URI buildResultLocationUri(HttpServletRequest httpServletRequest,
                                     Long queryId) {
    UriComponentsBuilder uriBuilder = (apiBaseUrl != null && !apiBaseUrl.isEmpty())
            ? ServletUriComponentsBuilder.fromUriString(apiBaseUrl)
            : ServletUriComponentsBuilder.fromRequestUri(httpServletRequest);

    return uriBuilder.replacePath("")
            .pathSegment("api", "v1", "query-handler", "result", String.valueOf(queryId))
            .build()
            .toUri();
  }

  @GetMapping(path = "/result/{id}")
  @Deprecated
  public ResponseEntity<Object> getQueryResult(@PathVariable("id") Long queryId,
      Authentication authentication) {

    String authorId;
    try {
      authorId = queryHandlerService.getAuthorId(queryId);
    } catch (QueryNotFoundException e) {
      return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    if (authorId.equalsIgnoreCase(authentication.getName())) {
      var queryResult = queryHandlerService.getQueryResult(queryId, ResultDetail.SUMMARY);
      if (queryResult.getTotalNumberOfPatients() < privacyThresholdResults) {
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
      } else {
        return new ResponseEntity<>(queryResult, HttpStatus.OK);
      }
    } else {
      return new ResponseEntity<>(HttpStatus.FORBIDDEN);
    }
  }
}
