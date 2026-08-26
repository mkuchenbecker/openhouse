package harness

/** Mixes the standard and RTAS scenario-owned case lists into one catalog source. */
object Scenarios
    extends DmlScenarios
    with NestedTypesScenarios
    with MaintControlScenarios
    with ForkScenarios
    with NegativeDdlScenarios
    with InteractionScenarios
    with SurfaceScenarios
    with HazardReaderWriterScenarios
    with ImplementationPinScenarios
    with RtasDmlScenarios
    with RtasDdlScenarios
    with RtasInteractionScenarios
    with RtasSurfaceScenarios
    with RtasHazardScenarios
