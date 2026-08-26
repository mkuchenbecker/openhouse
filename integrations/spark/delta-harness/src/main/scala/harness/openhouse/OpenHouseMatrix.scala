package harness

/** Mixes the standard, RTAS, and merge-on-read scenario lists into one catalog source. */
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
    with MorDmlScenarios
    with MorMaintScenarios
    with MorReaderWriterScenarios
    with MorInteractionScenarios
    with MorSurfaceScenarios
    with MorForkScenarios
