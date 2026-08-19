"""Public v0.2 product-contract surface."""

from .api import (
    API_VERSION,
    OBJECT_API_ROUTES,
    PAGES,
    TOP_LEVEL_SPACES,
    authorize_operation,
    authorize_page,
    validate_api_contract,
)
from .auth import AuthenticatedPrincipal, PrincipalClaims
from .enums import (
    ActorRole,
    ArtifactSourceKind,
    CloudSafeKind,
    DataClass,
    DecisionOutcome,
    GateKind,
    InteractionState,
    ObjectType,
    ProposalState,
    ReleaseState,
    Space,
    WorkState,
)
from .models import (
    AgentRuntime,
    ArtifactReference,
    Capability,
    ContractObject,
    ControlEvent,
    Decision,
    Interaction,
    Mission,
    Node,
    Observation,
    ProductArea,
    Proposal,
    Release,
    Run,
    WorkItem,
    contract_from_dict,
    record_hash,
)
from .rules import (
    ArtifactCandidate,
    ArtifactProvenance,
    CrossSpaceReference,
    IsolatedArtifactPolicyRegistry,
)

__all__ = [
    "API_VERSION", "OBJECT_API_ROUTES", "PAGES", "TOP_LEVEL_SPACES",
    "ActorRole", "AgentRuntime", "ArtifactCandidate", "ArtifactProvenance",
    "ArtifactReference",
    "ArtifactSourceKind", "AuthenticatedPrincipal", "Capability",
    "CloudSafeKind", "ContractObject", "ControlEvent", "CrossSpaceReference",
    "DataClass", "Decision", "DecisionOutcome", "GateKind", "Interaction",
    "InteractionState", "IsolatedArtifactPolicyRegistry", "Mission", "Node",
    "ObjectType", "Observation",
    "PrincipalClaims", "ProductArea", "Proposal", "ProposalState", "Release",
    "ReleaseState", "Run", "Space", "WorkItem", "WorkState",
    "authorize_operation", "authorize_page", "contract_from_dict", "record_hash",
    "validate_api_contract",
]
