package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class ContractTest {
  @SuppressWarnings("unchecked")
  @Test
  void openapiRoutesAndRecordFieldsMatchTheExecutableAdapter() throws Exception {
    LoaderOptions limits = new LoaderOptions();
    limits.setCodePointLimit(65536);
    limits.setNestingDepthLimit(32);
    Map<String, Object> contract =
        new Yaml(new SafeConstructor(limits))
            .load(Files.readString(Path.of("..", "contracts", "openapi.yaml")));
    Map<String, Object> paths = (Map<String, Object>) contract.get("paths");
    Set<String> executable = new TreeSet<>();
    for (var method : Application.class.getDeclaredMethods()) {
      GetMapping get = method.getAnnotation(GetMapping.class);
      PostMapping post = method.getAnnotation(PostMapping.class);
      if (get != null) for (String path : get.value()) executable.add(path);
      if (post != null) for (String path : post.value()) executable.add(path);
    }
    assertEquals(paths.keySet(), executable);
    Map<String, Object> components = (Map<String, Object>) contract.get("components");
    Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
    Map<String, Class<?>> dto = new LinkedHashMap<>();
    dto.put("Start", Application.Start.class);
    dto.put("Signal", Application.Signal.class);
    dto.put("Resolution", Application.Resolution.class);
    dto.put("ClaimRequest", Application.ClaimRequest.class);
    dto.put("Completion", Application.Completion.class);
    dto.put("Failure", Application.Failure.class);
    dto.put("Claim", WorkflowStore.Claim.class);
    dto.put("Definition", Definition.class);
    dto.put("ExecutionInput", Data.ExecutionInput.class);
    dto.put("Output", Data.Output.class);
    dto.put("HistoryPage", HistoryPage.class);
    dto.put("HistoryEvent", Interpreter.Event.class);
    for (var entry : dto.entrySet()) {
      Map<String, Object> schema = (Map<String, Object>) schemas.get(entry.getKey());
      assertNotNull(schema, entry.getKey());
      Set<String> fields =
          Arrays.stream(entry.getValue().getRecordComponents())
              .map(java.lang.reflect.RecordComponent::getName)
              .collect(Collectors.toSet());
      assertEquals(
          fields, ((Map<String, Object>) schema.get("properties")).keySet(), entry.getKey());
      assertEquals(Boolean.FALSE, schema.get("additionalProperties"), entry.getKey());
    }
    Map<String, Object> definition = (Map<String, Object>) schemas.get("Definition"),
        definitionProperties = (Map<String, Object>) definition.get("properties"),
        nodes = (Map<String, Object>) definitionProperties.get("nodes"),
        node = (Map<String, Object>) nodes.get("items");
    assertEquals(
        Arrays.stream(Definition.Node.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(Collectors.toSet()),
        ((Map<String, Object>) node.get("properties")).keySet());
    for (Object choice : (List<Object>) ((Map<String, Object>) schemas.get("Value")).get("oneOf"))
      assertEquals(
          Set.of("type", "data"),
          ((Map<String, Object>) ((Map<String, Object>) choice).get("properties")).keySet());
    checkReferences(contract, contract);
  }

  @SuppressWarnings("unchecked")
  void checkReferences(Object node, Map<String, Object> root) {
    if (node instanceof Map<?, ?> map) {
      Object ref = map.get("$ref");
      if (ref != null) {
        assertTrue(ref.toString().startsWith("#/"));
        Object value = root;
        for (String segment : ref.toString().substring(2).split("/")) {
          assertTrue(value instanceof Map<?, ?>);
          value = ((Map<?, ?>) value).get(segment);
          assertNotNull(value, ref.toString());
        }
      }
      map.values().forEach(value -> checkReferences(value, root));
    } else if (node instanceof List<?> list) list.forEach(value -> checkReferences(value, root));
  }
}
