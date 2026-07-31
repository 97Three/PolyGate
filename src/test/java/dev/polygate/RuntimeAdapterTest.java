package dev.polygate;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;

class RuntimeAdapterTest {
  final ObjectMapper json = new ObjectMapper();

  ObjectNode fixture(String name) throws Exception {
    return (ObjectNode)
        json.readTree(
            getClass()
                .getResourceAsStream(
                    "/runtime-adapters/qwen-llama-cpp-" + name + "-v1alpha1.json"));
  }

  @Test
  void acceptsOriginalWireFixtures() throws Exception {
    RuntimeAdapterController.validate(fixture("capabilities"));
    RuntimeAdapterController.validate(fixture("compute-inventory"));
  }

  @Test
  void rejectsMutationAndDuplicateOperation() throws Exception {
    var c = fixture("capabilities");
    ((ObjectNode) c.path("spec").path("operations").get(0)).put("method", "POST");
    assertThatThrownBy(() -> RuntimeAdapterController.validate(c))
        .isInstanceOf(GatewayException.class);
  }

  @Test
  void rejectsImpossibleMemoryAndDuplicateGpu() throws Exception {
    var c = fixture("compute-inventory");
    ((ObjectNode) c.path("nodes").get(0).path("gpus").get(0).path("memory"))
        .put("availableBytes", 999999999999L);
    assertThatThrownBy(() -> RuntimeAdapterController.validate(c))
        .isInstanceOf(GatewayException.class);
  }

  @Test
  void rejectsPartitionWithoutPhysicalParent() throws Exception {
    var c = fixture("compute-inventory");
    ((ObjectNode) c.path("nodes").get(0).path("gpus").get(0))
        .put("deviceClass", "partition")
        .put("parentGpuId", "missing");
    assertThatThrownBy(() -> RuntimeAdapterController.validate(c))
        .isInstanceOf(GatewayException.class);
  }
}
