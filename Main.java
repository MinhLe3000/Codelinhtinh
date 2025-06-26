import io.socket.emitter.Emitter;
import jsclub.codefest.sdk.Hero;
import jsclub.codefest.sdk.algorithm.PathUtils;
import jsclub.codefest.sdk.base.Node;
import jsclub.codefest.sdk.model.GameMap;
import jsclub.codefest.sdk.model.obstacles.Obstacle;
import jsclub.codefest.sdk.model.players.Player;
import jsclub.codefest.sdk.model.weapon.Weapon;
import jsclub.codefest.sdk.model.equipments.Armor;
import jsclub.codefest.sdk.model.equipments.HealingItem;
import jsclub.codefest.sdk.model.npcs.Enemy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class Main {
    private static final String SERVER_URL = "https://cf25-server.jsclub.dev";
    private static final String GAME_ID = "190417"; // Nhập ID game
    private static final String PLAYER_NAME = "hi";   // Tên bot
    private static final String SECRET_KEY = "sk-Boq-Zyy6Rrid_kkFFxFucQ:Ufgc8OS8UWSnk5pIKT7IYak9ovOa4_s8YDoNKKaVKES-ryONOrCCd2BJQLvAITl48wcnm6LF8IMHcrNLLuNYOA"; // Key BTC

    public static void main(String[] args) throws IOException {
        Hero hero = new Hero(GAME_ID, PLAYER_NAME, SECRET_KEY);
        hero.setOnMapUpdate(new MapUpdateListener(hero));
        hero.start(SERVER_URL);
    }
}

class MapUpdateListener implements Emitter.Listener {
    private final Hero hero;
    private static final int LOW_HEALTH_THRESHOLD = 50; // Ngưỡng máu thấp để hồi phục

    public MapUpdateListener(Hero hero) {
        this.hero = hero;
    }

    @Override
    public void call(Object... args) {
        try {
            if (args == null || args.length == 0) return;

            GameMap gameMap = hero.getGameMap();
            gameMap.updateOnUpdateMap(args[0]);
            Player player = gameMap.getCurrentPlayer();

            if (player == null || player.getHealth() == 0) {
                System.out.println("Player is dead or data is not available.");
                return;
            }

            List<Node> avoidNodes = getNodesToAvoid(gameMap);

            // 1. Ưu tiên: Hồi máu nếu HP thấp
            if (player.getHealth() < LOW_HEALTH_THRESHOLD && !hero.getInventory().getListHealingItem().isEmpty()) {
                Optional<HealingItem> bestHealingItem = hero.getInventory().getListHealingItem().stream()
                        .max(Comparator.comparingInt(HealingItem::getHealingHP)); // Chọn item hồi nhiều máu nhất
                if (bestHealingItem.isPresent()) {
                    hero.useItem(bestHealingItem.get().getId());
                    System.out.println("Using healing item. Current HP: " + player.getHealth());
                    return;
                }
            }

            // 2. Ưu tiên: Vào bo nếu ngoài vùng an toàn
            // Đã sửa: Thêm gameMap.getMapSize() làm tham số thứ 3
            if (!PathUtils.checkInsideSafeArea(player, gameMap.getSafeZone(), gameMap.getMapSize())) {
                Node safeTarget = findNearestSafeCell(gameMap, player, avoidNodes);
                if (safeTarget != null) {
                    String safePath = PathUtils.getShortestPath(gameMap, avoidNodes, player, safeTarget, false);
                    if (safePath != null && !safePath.isEmpty()) {
                        hero.move(safePath);
                        System.out.println("Moving to safe zone.");
                        return;
                    }
                }
            }
            // Ưu tiên 1
            // 4. Ưu tiên: Đập rương nếu ở gần hoặc trên đường đi
            Obstacle nearestChest = getNearestChest(gameMap, player);
            if (nearestChest != null) {
                if (isAdjacent(player, nearestChest)) { // Sử dụng isAdjacent với Node
                    String dir = getDirection(player, nearestChest); // Sử dụng getDirection với Node
                    if (dir != null) {
                        hero.attack(dir); // Dùng melee để đập rương
                        System.out.println("Attacking nearest chest.");
                        return;
                    }
                } else {
                    String pathToChest = PathUtils.getShortestPath(gameMap, avoidNodes, player, nearestChest, false);
                    if (pathToChest != null && !pathToChest.isEmpty()) {
                        hero.move(pathToChest);
                        System.out.println("Moving towards nearest chest.");
                        return;
                    }
                }
            }
            // Ưu tiên 2
            // 5. Ưu tiên: Nhặt vũ khí tốt hơn (súng, melee, throwable, special)
            // Logic nhặt súng
            Weapon currentGun = hero.getInventory().getGun();
            Weapon bestGunOnMap = getBestWeapon(gameMap.getAllGun(), player);
            if (bestGunOnMap != null && (currentGun == null || shouldUpgradeWeapon(currentGun, bestGunOnMap))) {
                if (bestGunOnMap.getX() == player.getX() && bestGunOnMap.getY() == player.getY()) {
                    hero.pickupItem();
                    System.out.println("Picking up gun.");
                } else {
                    String path = PathUtils.getShortestPath(gameMap, avoidNodes, player, bestGunOnMap, false);
                    if (path != null && !path.isEmpty()) {
                        hero.move(path);
                        System.out.println("Moving to pick up gun.");
                    }
                }
                return;
            }

            // Logic nhặt melee
            Weapon currentMelee = hero.getInventory().getMelee();
            Weapon bestMeleeOnMap = getBestWeapon(gameMap.getAllMelee(), player);
            if (bestMeleeOnMap != null && (currentMelee == null || shouldUpgradeWeapon(currentMelee, bestMeleeOnMap))) {
                if (bestMeleeOnMap.getX() == player.getX() && bestMeleeOnMap.getY() == player.getY()) {
                    hero.pickupItem();
                    System.out.println("Picking up melee weapon.");
                } else {
                    String path = PathUtils.getShortestPath(gameMap, avoidNodes, player, bestMeleeOnMap, false);
                    if (path != null && !path.isEmpty()) {
                        hero.move(path);
                        System.out.println("Moving to pick up melee weapon.");
                    }
                }
                return;
            }

            // Logic nhặt throwable
            Weapon currentThrowable = hero.getInventory().getThrowable();
            Weapon bestThrowableOnMap = getBestWeapon(gameMap.getAllThrowable(), player);
            if (bestThrowableOnMap != null && (currentThrowable == null || shouldUpgradeWeapon(currentThrowable, bestThrowableOnMap))) {
                if (bestThrowableOnMap.getX() == player.getX() && bestThrowableOnMap.getY() == player.getY()) {
                    hero.pickupItem();
                    System.out.println("Picking up throwable weapon.");
                } else {
                    String path = PathUtils.getShortestPath(gameMap, avoidNodes, player, bestThrowableOnMap, false);
                    if (path != null && !path.isEmpty()) {
                        hero.move(path);
                        System.out.println("Moving to pick up throwable weapon.");
                    }
                }
                return;
            }

            // Logic nhặt special
            Weapon currentSpecial = hero.getInventory().getSpecial();
            Weapon bestSpecialOnMap = getBestWeapon(gameMap.getAllSpecial(), player);
            if (bestSpecialOnMap != null && (currentSpecial == null || shouldUpgradeWeapon(currentSpecial, bestSpecialOnMap))) {
                if (bestSpecialOnMap.getX() == player.getX() && bestSpecialOnMap.getY() == player.getY()) {
                    hero.pickupItem();
                    System.out.println("Picking up special weapon.");
                } else {
                    String path = PathUtils.getShortestPath(gameMap, avoidNodes, player, bestSpecialOnMap, false);
                    if (path != null && !path.isEmpty()) {
                        hero.move(path);
                        System.out.println("Moving to pick up special weapon.");
                    }
                }
                return;
            }

            // 3. Ưu tiên: Tấn công kẻ địch nếu có thể
            Player nearestEnemy = getNearestEnemy(gameMap, player);
            if (nearestEnemy != null) {
                // Kiểm tra và sử dụng súng
                if (hero.getInventory().getGun() != null) {
                    Weapon gun = hero.getInventory().getGun();
                    // Kiểm tra tầm bắn và đường bắn thẳng
                    if (PathUtils.distance(player, nearestEnemy) <= gun.getRange() && isShootableInLine(player, nearestEnemy)) {
                        String dir = getDirection(player, nearestEnemy);
                        if (dir != null) {
                            hero.shoot(dir);
                            System.out.println("Shooting at enemy: " + nearestEnemy.getID());
                            return;
                        }
                    }
                }
                // Kiểm tra và sử dụng melee nếu kẻ địch ở gần
                if (hero.getInventory().getMelee() != null) {
                    Weapon melee = hero.getInventory().getMelee();
                    // Kiểm tra tầm đánh melee (thường là 1 ô kề)
                    if (PathUtils.distance(player, nearestEnemy) <= melee.getRange() && isAdjacent(player, nearestEnemy)) {
                        String dir = getDirection(player, nearestEnemy);
                        if (dir != null) {
                            hero.attack(dir);
                            System.out.println("Attacking enemy with melee: " + nearestEnemy.getID());
                            return;
                        }
                    }
                }
                // Kiểm tra và sử dụng throwable nếu có và kẻ địch trong tầm ném
                if (hero.getInventory().getThrowable() != null) {
                    Weapon throwable = hero.getInventory().getThrowable();
                    // Giả định tầm ném là range của throwable
                    if (PathUtils.distance(player, nearestEnemy) <= throwable.getRange()) {
                        String dir = getDirection(player, nearestEnemy);
                        if (dir != null) {
                            // distance là int, cần ép kiểu nếu PathUtils.distance trả về double
                            hero.throwItem(dir, (int) PathUtils.distance(player, nearestEnemy));
                            System.out.println("Throwing item at enemy: " + nearestEnemy.getID());
                            return;
                        }
                    }
                }
                // Kiểm tra và sử dụng special nếu có
                if (hero.getInventory().getSpecial() != null) {
                    Weapon special = hero.getInventory().getSpecial();
                    // Giả định tầm sử dụng special là range của special
                    if (PathUtils.distance(player, nearestEnemy) <= special.getRange()) {
                        String dir = getDirection(player, nearestEnemy);
                        if (dir != null) {
                            hero.useSpecial(dir);
                            System.out.println("Using special item on enemy: " + nearestEnemy.getID());
                            return;
                        }
                    }
                }

                // Nếu không thể tấn công, di chuyển đến gần kẻ địch để tấn công
                String pathToEnemy = PathUtils.getShortestPath(gameMap, avoidNodes, player, nearestEnemy, false);
                if (pathToEnemy != null && !pathToEnemy.isEmpty()) {
                    hero.move(pathToEnemy);
                    System.out.println("Moving towards nearest enemy: " + nearestEnemy.getID());
                    return;
                }
            }


            // 7. Di chuyển ngẫu nhiên hoặc tìm kiếm nếu không có mục tiêu ưu tiên
            // Có thể thêm logic di chuyển khám phá bản đồ ở đây
            System.out.println("No immediate action. Player HP: " + player.getHealth());

        } catch (Exception e) {
            System.err.println("Critical error in map update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==== Các hàm phụ trợ ====

    /**
     * Lấy danh sách các Node cần tránh.
     * Bao gồm các vật cản không thể đi qua, người chơi khác, bẫy, và vị trí của kẻ thù NPC.
     * Dựa trên các hàm có sẵn trong GameMap.md và Entity.md
     */
    private List<Node> getNodesToAvoid(GameMap map) {
        List<Node> nodes = new ArrayList<>();

        // Thêm các vật cản không thể đi qua (dựa vào getObstaclesByTag)
        // Các vật cản có tag "CAN_GO_THROUGH" sẽ không được thêm vào đây.
        // Vì không có getTags() trên Obstacle, ta phải dựa vào các hàm getObstaclesByTag.
        // Giả định "DESTRUCTIBLE" và "PULLABLE_ROPE" là các vật cản không thể đi qua.
        nodes.addAll(map.getObstaclesByTag("DESTRUCTIBLE"));
        nodes.addAll(map.getObstaclesByTag("PULLABLE_ROPE"));
        // Các vật cản khác không có tag CAN_GO_THROUGH cũng cần được thêm vào
        // Tuy nhiên, GameMap.md không cung cấp cách lấy tất cả các vật cản không có tag CAN_GO_THROUGH
        // mà chỉ cung cấp getListObstaclesInit() và getListObstaclesUpdate()
        // và getObstaclesByTag().
        // Để đơn giản và tuân thủ, ta chỉ tránh những loại được liệt kê rõ ràng là vật cản cứng.

        // Thêm vị trí của người chơi khác
        map.getOtherPlayerInfo().forEach(nodes::add);

        // Thêm vị trí của bẫy
        map.getObstaclesByTag("TRAP").forEach(nodes::add);

        // Thêm vị trí của kẻ thù NPC
        map.getListEnemies().forEach(enemy -> nodes.add(new Node(enemy.getX(), enemy.getY())));

        return nodes;
    }

    /**
     * Tìm ô an toàn gần nhất trong vùng an toàn.
     * Đã sửa: PathUtils.checkInsideSafeArea yêu cầu 3 tham số: Node, int safeAreaSize, int mapSize
     */
    private Node findNearestSafeCell(GameMap gameMap, Player player, List<Node> avoidNodes) {
        int mapSize = gameMap.getMapSize();
        int safeZone = gameMap.getSafeZone();

        Node bestNode = null;
        double bestDist = Double.MAX_VALUE;

        // Duyệt qua tất cả các ô trên bản đồ
        for (int x = 0; x < mapSize; x++) {
            for (int y = 0; y < mapSize; y++) {
                Node node = new Node(x, y);
                // Đã sửa: Thêm gameMap.getMapSize() làm tham số thứ 3
                if (PathUtils.checkInsideSafeArea(node, safeZone, mapSize) && !avoidNodes.contains(node)) {
                    double dist = PathUtils.distance(player, node);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestNode = node;
                    }
                }
            }
        }
        return bestNode;
    }

    /**
     * Lấy vũ khí tốt nhất (ưu tiên sát thương, sau đó độ hiếm) từ một danh sách.
     * Chỉ sử dụng các hàm getDamage() và getRarity() từ Weapon.
     * Các đối tượng Weapon có getX(), getY() nên có thể coi là Node.
     * Đã sửa: Sử dụng thenComparingDouble cho PathUtils.distance
     */
    private Weapon getBestWeapon(List<Weapon> weapons, Player player) {
        return weapons.stream()
                .filter(w -> w.getX() != -1 && w.getY() != -1) // Giả định -1 là không còn trên bản đồ
                .max(Comparator
                        .comparingInt(Weapon::getDamage) // Ưu tiên sát thương cao hơn
                        .thenComparingInt(Weapon::getRarity) // Sau đó ưu tiên độ hiếm cao hơn
                        .thenComparingDouble(w -> -PathUtils.distance(player, w))) // Cuối cùng ưu tiên gần hơn
                .orElse(null);
    }

    /**
     * Kiểm tra xem có nên nâng cấp vũ khí hiện tại hay không.
     * So sánh vũ khí hiện tại với một vũ khí tốt nhất cụ thể trên bản đồ.
     * Chỉ sử dụng getDamage() và getRarity() từ Weapon.
     */
    private boolean shouldUpgradeWeapon(Weapon currentWeapon, Weapon potentialNewWeapon) {
        if (currentWeapon == null) return true; // Chưa có vũ khí thì luôn nên nhặt
        if (potentialNewWeapon == null) return false; // Không có vũ khí mới để so sánh

        // So sánh theo sát thương, sau đó độ hiếm
        if (potentialNewWeapon.getDamage() > currentWeapon.getDamage()) {
            return true;
        }
        if (potentialNewWeapon.getDamage() == currentWeapon.getDamage() && potentialNewWeapon.getRarity() > currentWeapon.getRarity()) {
            return true;
        }
        return false;
    }

    /**
     * Lấy giáp tốt nhất (ưu tiên giảm sát thương, sau đó HP) từ một danh sách.
     * Chỉ sử dụng các hàm getHealthPoint(), getDamageReduce() từ Armor.
     * Các đối tượng Armor có getX(), getY() nên có thể coi là Node.
     * Đã sửa: Sử dụng thenComparingDouble cho PathUtils.distance.
     */
    /**
     * Kiểm tra xem có nên nâng cấp giáp hiện tại hay không.
     * Chỉ sử dụng getHealthPoint(), getDamageReduce() từ Armor.
     * Đã sửa: Loại bỏ getRarity() cho Armor.
     */
    private boolean shouldUpgradeArmor(Armor currentArmor, Armor potentialNewArmor) {
        if (currentArmor == null) return true;
        if (potentialNewArmor == null) return false;

        // So sánh theo giảm sát thương, sau đó HP
        if (potentialNewArmor.getDamageReduce() > currentArmor.getDamageReduce()) {
            return true;
        }
        if (potentialNewArmor.getDamageReduce() == currentArmor.getDamageReduce() && potentialNewArmor.getHealthPoint() > currentArmor.getHealthPoint()) {
            return true;
        }
        // Loại bỏ so sánh Rarity vì Armor không có getRarity()
        return false;
    }

    /**
     * Lấy kẻ địch gần nhất.
     * Sử dụng getOtherPlayerInfo() từ GameMap.
     */
    private Player getNearestEnemy(GameMap gameMap, Player self) {
        return gameMap.getOtherPlayerInfo().stream()
                .min(Comparator.comparingDouble(enemy -> PathUtils.distance(self, enemy)))
                .orElse(null);
    }

    /**
     * Lấy rương gần nhất.
     * Sử dụng getObstaclesByTag("DESTRUCTIBLE") từ GameMap.
     */
    private Obstacle getNearestChest(GameMap map, Player player) {
        return map.getObstaclesByTag("DESTRUCTIBLE").stream()
                .min(Comparator.comparingDouble(chest -> PathUtils.distance(player, chest)))
                .orElse(null);
    }

    /**
     * Kiểm tra xem hai Node có kề nhau không (khoảng cách Manhattan = 1).
     * Sử dụng getX(), getY() từ Node.
     */
    private boolean isAdjacent(Node p1, Node p2) {
        int dx = Math.abs(p1.getX() - p2.getX());
        int dy = Math.abs(p1.getY() - p2.getY());
        return (dx + dy == 1);
    }

    /**
     * Lấy hướng di chuyển từ Node 'from' đến Node 'to'.
     * Sử dụng getX(), getY() từ Node.
     */
    private String getDirection(Node from, Node to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        if (dx == 1 && dy == 0) return "r";
        if (dx == -1 && dy == 0) return "l";
        if (dx == 0 && dy == 1) return "u";
        if (dx == 0 && dy == -1) return "d";
        return null;
    }

    /**
     * Kiểm tra xem có thể bắn theo đường thẳng giữa hai người chơi không.
     * Sử dụng getX(), getY() từ Player.
     */
    private boolean isShootableInLine(Player from, Player to) {
        return from.getX() == to.getX() || from.getY() == to.getY();
    }
}
