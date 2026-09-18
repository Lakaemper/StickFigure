from world import World
from config_loader import load_stick_figure
from renderer import Renderer


def main():
    world = World()
    ids = load_stick_figure(world, "config.json")
    Renderer(world, head_index=ids["head"], lift_anchor_index=ids["foot_anchor_r"],
             hip_joint_indices=[ids["joint_hip_l"], ids["joint_hip_r"]],
             foot_anchor_indices=[ids["foot_anchor_l"], ids["foot_anchor_r"]]).run()


if __name__ == "__main__":
    main()
