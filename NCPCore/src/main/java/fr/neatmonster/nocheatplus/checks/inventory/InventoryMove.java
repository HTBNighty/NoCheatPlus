/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.inventory;

import java.util.LinkedList;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.block.Block;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.fight.FightData;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.BridgeEnchant;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveInfo;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.checks.moving.magic.Magic;
import fr.neatmonster.nocheatplus.checks.moving.model.ModelFlying;
import fr.neatmonster.nocheatplus.utilities.StringUtil;


/**
 * A check to prevent players from interacting with their inventory if they shouldn't be allowed to.
 */
public class InventoryMove extends Check {
    

   private final AuxMoving aux = NCPAPIProvider.getNoCheatPlusAPI().getGenericInstance(AuxMoving.class);


  /**
    * Instanties a new InventoryMove check
    *
    */
    public InventoryMove() {
        super(CheckType.INVENTORY_INVENTORYMOVE);
    }
    

  /**
    * Checks a player
    * @param player
    * @param data
    * @param pData
    * @param cc
    * @param type
    * @return true if successful
    *
    */
    public boolean check(final Player player, final InventoryData data, final IPlayerData pData, final InventoryConfig cc, final SlotType type){
        
        // TODO: Inventory click + swing packet/attack ? Would be a way to detect hitting+attack (which would catch careless players
        // who do not set their killaura to stop attacking if the inv is opened, although it should probably be done in the fight section (Fight.ImpossibleHit?))

        // Consider skipping the check altogether if the player is exposed to dolphin's grace and has boots with DepthStrider III. Players go uncomfortably fast with this combo.

       /* 
        * Important: MC allows players to swim on the ground but the status is not *consistently* reflected back to the server 
        * (while still allowing them to move at swimming speed) instead, isSprinting() will return. Observed in both Spigot and PaperMC.
        *
        */
        
        // Shortcuts:
        // Movement
        final MovingData mData = pData.getGenericInstance(MovingData.class);
        final PlayerMoveData thisMove = mData.playerMoves.getCurrentMove();
        final PlayerMoveData lastMove = mData.playerMoves.getFirstPastMove();
        final PlayerMoveInfo moveInfo = aux.usePlayerMoveInfo();
        final PlayerLocation from = moveInfo.from;
        final PlayerLocation to = moveInfo.to;
        final boolean isSamePos = from.isSamePos(to); // The player is standing still, no XYZ distances.
        final boolean thisMoveOnGround = thisMove.touchedGround || thisMove.from.onGround || thisMove.to.onGround;
        final boolean fromLiquid = thisMove.from.inLiquid;
        final boolean toLiquid = thisMove.to.inLiquid;
        
        // Others
        final FightData fData = pData.getGenericInstance(FightData.class);
        final boolean creative = player.getGameMode() == GameMode.CREATIVE && ((type == SlotType.QUICKBAR) || cc.invMoveDisableCreative);
        final boolean isMerchant = (player.getOpenInventory().getTopInventory().getType() == InventoryType.MERCHANT); 
        final long currentEvent = System.currentTimeMillis();
        final boolean noIceTick = (mData.sfOnIce == 0); 
        final boolean isSprintLost = (mData.lostSprintCount > 0);
        final boolean isCollidingWithEntities = CollisionUtil.isCollidingWithEntities(player, true) && Bridge1_9.hasElytra(); // Dirty way to check if the server is 1.9+
        final Block blockUnder = player.getLocation().subtract(0, 0.6, 0).getBlock();
        final Material blockAbove = player.getLocation().add(0, 0.10, 0).getBlock().getType();
        // Used to workaround issues with splash moves: water-water-air-air-air-water-air
        final boolean movingOnSurface = blockAbove != null && blockUnder != null && blockAbove.name().endsWith("AIR") 
                                        && (blockUnder.getType().toString().endsWith("WATER") || blockUnder.getType().toString().endsWith("LAVA"));
        // Used to fix up issues with players immediately clicking in inventories after having stopped to swim.
        final boolean swimming = (Bridge1_13.isSwimming(player) || mData.timeSwimming + 1000 > currentEvent);
        List<String> tags = new LinkedList<String>();
        
        boolean cancel = false;
        boolean violation = false;
        double friction = lastMove.hDistance * mData.lastFrictionHorizontal;
        double hDistDiff = Math.abs(thisMove.hDistance - lastMove.hDistance);
        double deltaFrict = Math.abs(friction - hDistDiff);
        double marginH = 0.0; double hDistMin = 0.0;
        final double[] result = setMinHDistAndFriction(player, pData, mData, cc, thisMoveOnGround, thisMove, 
                                                       lastMove, marginH, hDistMin, movingOnSurface, swimming);
        marginH = result[0];
        hDistMin = result[1];

        // Debug first.
        if (pData.isDebugActive(CheckType.INVENTORY_INVENTORYMOVE)) {
            player.sendMessage("hDistance= " + StringUtil.fdec3.format(thisMove.hDistance) 
                + " hDistanceMin= " + StringUtil.fdec3.format(hDistMin)
                + " deltaFrict= " + StringUtil.fdec3.format(deltaFrict)
                + " marginH= " + StringUtil.fdec3.format(marginH)
                + " yDistance= " + StringUtil.fdec3.format(thisMove.yDistance)
                + " liqTick= " + mData.liqtick);
        }
        




        // Clicking while using/consuming an item
        if (mData.isusingitem && !isMerchant) { 
            tags.add("usingitem");
            violation = true;
        }

        // ... while attacking
        // TODO: Doesn't seem to work...
        // else if (fData.noSwingPacket){
        //     tags.add("armswing");
        //     violation = true;
        // }

        // ...while flying
        // (not consistent with the delta outputs, especially if flying backwards)
        // else if (player.isFlying() && (thisMove.yDistance > 0.1 || deltaFrict >= marginH)){ 
        //     violation = true;
        //     tags.add("isFlying");
        // }

        // ... while swimming
        // Having a positive y delta means that the player is swimming upwards (not possible, as that would mean a click+jump bar pressed...).
        // if delta>marginH, we assume the player to be intentionally moving and not being moved by friction.
        else if (swimming && thisMove.hDistance > hDistMin && (deltaFrict > marginH || thisMove.yDistance > 0.1) && !isSamePos){ 
            tags.add("isSwimming");
            violation = true;   
        }
        
        // ... while bunnyhopping
        else if (thisMove.bunnyHop && !mData.isVelocityJumpPhase()){
            violation = true;
            tags.add("hopClick");
        }

        // ... while being dead or sleeping (-> Is it even possible?)
        else if (player.isDead() || player.isSleeping()) {
            tags.add(player.isDead() ? "isDead" : "isSleeping");
            violation = true;
        }

        // ...  while sprinting
        else if (player.isSprinting() && thisMove.hDistance > thisMove.walkSpeed && !player.isFlying() && !isSprintLost){
            tags.add("isSprinting");
            
            // Use the marginH as a workaround to the MC bug described above (The player is swimming on the ground but isSprinting() is returned instead).
            // On ground in liquid
            if (toLiquid && fromLiquid && deltaFrict > marginH && thisMove.hDistance > hDistMin) violation = true;
            // (no else as the delta could be < invSlowDown (stop). Explicitly require the player to not be in a liquid.
            // On ground
            else if (!toLiquid && !fromLiquid) violation = true;
        }

        // ... while sneaking
        else if (player.isSneaking() && ((currentEvent - data.lastMoveEvent) < 65) && thisMoveOnGround
                && !(thisMove.downStream & mData.isdownstream) // Fix thisMove#downStream, rather. Both have to be true
                && !isCollidingWithEntities
                &&  noIceTick
                && !isSamePos // Need to ensure that players can toggle sneak with their inv open. (around mc 1.13)
                ){
            tags.add("isSneaking");

            if (hDistDiff < cc.invMoveHdistLeniency && thisMove.hDistance > hDistMin){
                
                // Use the marginH as a workaround to the MC bug described above (The player is swimming and sneaking on the ground but only isSneaking() is returned)
                // On ground in liquid
                if (toLiquid && fromLiquid && deltaFrict > marginH) violation = true;
                // On ground
                else if (!toLiquid && !fromLiquid) violation = true;
            }
        }
        
        // Last resort, check if the player is actively moving while clicking in their inventory
        else {
            
            if (((currentEvent - data.lastMoveEvent) < 65)
                && !(thisMove.downStream & mData.isdownstream) 
                && !mData.isVelocityJumpPhase()
                && !isCollidingWithEntities
                && !player.isInsideVehicle() 
                &&  noIceTick 
                && !isSamePos 
                ){ 
                tags.add("moving");

                if (hDistDiff < cc.invMoveHdistLeniency && thisMove.hDistance > hDistMin && thisMoveOnGround) {
                    
                    // Walking on the ground in a liquid
                    if (toLiquid && fromLiquid && deltaFrict > marginH) violation = true;
                    // On ground 
                    else if (!toLiquid && !fromLiquid) violation = true; 
                }
                // Moving inside liquid (but not on the ground)
                else if (fromLiquid && toLiquid && mData.liqtick > 3 && (deltaFrict > marginH || thisMove.yDistance > 0.1) 
                        && thisMove.hDistance > hDistMin){
                    violation = true;
                } 
                // Moving above liquid surface
                else if (movingOnSurface && deltaFrict > marginH && thisMove.hDistance > hDistMin){ 
                    violation = true;
                }
            }
        }
    

        // Handle violations 
        if (violation && !creative) {
            data.invMoveVL += 1D;
            final ViolationData vd = new ViolationData(this, player, data.invMoveVL, 1D, pData.getGenericInstance(InventoryConfig.class).invMoveActionList);
            if (vd.needsParameters()) vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
            cancel = executeActions(vd).willCancel();
        }
        
        // Cooldown
        else data.invMoveVL *= 0.96D;
    
        return cancel;
    }




  /**
    * Set the minimum horizontal distance/friction required for the checks to activate
    * @param player
    * @param pData
    * @param mData
    * @param thisMoveOnGround from/to/or touched the ground due to a lostGround workaround being applied.
    * @param thisMove
    * @param lastMove
    *
    * @return marginH,hDistMin
    */
    private double[] setMinHDistAndFriction(final Player player, final IPlayerData pData, final MovingData mData, final InventoryConfig cc,
                                           final boolean thisMoveOnGround, final PlayerMoveData thisMove, final PlayerMoveData lastMove,
                                           double marginH, double hDistMin, final boolean movingOnSurface, final boolean swimming){

        // TODO: Clean this magic fumbling and make things configurable(!)
        
        // In liquid
        if ((thisMove.from.inLiquid && thisMove.to.inLiquid) && mData.liqtick > 2 && !player.isFlying()) {
            // isSprinting is used just in case the swimming status is bugged and not reflected back to the server
            marginH = (swimming || player.isSprinting()) ? 0.099 : (player.isSneaking() ? 0.030 : 0.089); 
            hDistMin = (swimming || player.isSprinting()) ? 0.189 : (player.isSneaking() ? 0.031 : 0.075);
        }
        // Surface level
        else if (movingOnSurface && !player.isFlying()){
            marginH = (swimming || player.isSprinting()) ? 0.099 : 0.084;
            hDistMin = (swimming || player.isSprinting()) ? 0.170 : 0.108;
        }
        // Sneaking on the ground. Multiply the minimum hDist by the current walk speed.
        else if (player.isSneaking()){
            hDistMin = 0.050 * thisMove.walkSpeed / 100D;
        }
        // todo... 
        else if (player.isFlying()){
            final ModelFlying model = thisMove.modelFlying;
            marginH = (player.isSprinting() ? Magic.FRICTION_MEDIUM_AIR * model.getHorizontalModSprint() : Magic.FRICTION_MEDIUM_AIR) * mData.flySpeed / 100D;
            hDistMin = (player.isSprinting() ? 0.40 * model.getHorizontalModSprint() : 0.40) * mData.flySpeed / 100D;
        }
        // TODO: Ice friction
        // Fallback to default min hDist.
        else hDistMin = cc.invMoveHdistMin;


        // If in water, we need to account for all related enchants.
        if (thisMove.from.inWater || thisMove.to.inWater){ // From testing (lava): in-air -> splash move (lava) with delta 0.04834
  
            final int depthStriderLevel = BridgeEnchant.getDepthStriderLevel(player);
            if (depthStriderLevel > 0) {
                marginH *= Magic.modDepthStrider[depthStriderLevel] + 0.8;
                hDistMin *= Magic.modDepthStrider[depthStriderLevel] + 0.8;
            }

            if (!Double.isInfinite(Bridge1_13.getDolphinGraceAmplifier(player))) {
                marginH *= Magic.modDolphinsGrace;
                hDistMin *= Magic.modDolphinsGrace;

                if (depthStriderLevel > 1) {
                    marginH *= 1.6 + 0.20 * depthStriderLevel; 
                    hDistMin *= 1.6 + 0.20 * depthStriderLevel;
                }
            }
        }
        // Lava
        else {
            // Walking on ground in lava
            if (thisMoveOnGround) marginH = 0.038; // From testings: 0.0399...
        }

        return new double[] {marginH, hDistMin};
    }
}