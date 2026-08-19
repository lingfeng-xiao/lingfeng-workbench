"""Closed vocabulary for the v0.2 product contract."""

from enum import StrEnum


class Space(StrEnum):
    MY_WORK = "my_work"
    WORKBENCH = "workbench"


class ObjectType(StrEnum):
    WORK_ITEM = "work_item"
    MISSION = "mission"
    RUN = "run"
    INTERACTION = "interaction"
    NODE = "node"
    AGENT_RUNTIME = "agent_runtime"
    ARTIFACT_REFERENCE = "artifact_reference"
    CONTROL_EVENT = "control_event"
    PRODUCT_AREA = "product_area"
    CAPABILITY = "capability"
    OBSERVATION = "observation"
    PROPOSAL = "proposal"
    RELEASE = "release"
    DECISION = "decision"


class DataClass(StrEnum):
    CONTROL = "control"
    CLOUD_SAFE = "cloud_safe"
    LOCAL_ONLY = "local_only"
    SECRET = "secret"


class CloudSafeKind(StrEnum):
    WORKBENCH_DESIGN = "workbench_design"
    WORKBENCH_TEST_REPORT = "workbench_test_report"
    WORKBENCH_SCREENSHOT = "workbench_screenshot"
    SYNTHETIC_FIXTURE = "synthetic_fixture"
    USER_CONFIRMED_EXPORT = "user_confirmed_export"


class ArtifactSourceKind(StrEnum):
    WORKBENCH_DESIGN = "workbench_design"
    WORKBENCH_TEST_REPORT = "workbench_test_report"
    WORKBENCH_SCREENSHOT = "workbench_screenshot"
    SYNTHETIC_FIXTURE = "synthetic_fixture"
    USER_EXPORT = "user_export"
    COMPANY_CODE = "company_code"
    SOURCE_DIFF = "source_diff"
    RAW_LOG = "raw_log"
    SQL = "sql"
    DATABASE_EXPORT = "database_export"
    CUSTOMER_DATA = "customer_data"
    PRODUCTION_DATA = "production_data"
    BUSINESS_TEST_REPORT = "business_test_report"
    BUILD_REPORT = "build_report"
    RUNTIME_CONVERSATION = "runtime_conversation"
    ABSOLUTE_PATH = "absolute_path"
    SECRET = "secret"
    UNKNOWN = "unknown"


class ActorRole(StrEnum):
    USER = "user"
    WORKBENCH = "workbench"
    HERMES = "hermes"
    AGENT_RUNTIME = "agent_runtime"


class GateKind(StrEnum):
    DESIGN = "g0_design"
    PROPOSAL = "g1_proposal"
    PR_MERGE = "g2_pr_merge"
    SENSITIVE_CHANGE = "g3_sensitive_change"
    RELEASE = "g4_release"
    ARTIFACT_EXPORT = "artifact_export"


class DecisionOutcome(StrEnum):
    ACCEPT = "accept"
    REJECT = "reject"
    SKIP = "skip"


class WorkState(StrEnum):
    DRAFT = "draft"
    READY = "ready"
    ACTIVE = "active"
    WAITING = "waiting"
    COMPLETED = "completed"
    FAILED = "failed"
    UNCERTAIN = "uncertain"
    CANCELLED = "cancelled"


class InteractionState(StrEnum):
    PENDING = "pending"
    RESOLVED = "resolved"
    EXPIRED = "expired"
    CANCELLED = "cancelled"


class ProposalState(StrEnum):
    DRAFT = "draft"
    PROPOSED = "proposed"
    ACCEPTED = "accepted"
    REJECTED = "rejected"
    WITHDRAWN = "withdrawn"


class ReleaseState(StrEnum):
    DRAFT = "draft"
    READY = "ready"
    RELEASED = "released"
    FAILED = "failed"
    ROLLED_BACK = "rolled_back"
