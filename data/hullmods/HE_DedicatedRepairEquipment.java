package data.hullmods;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import java.awt.Color;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.BuffManagerAPI;
import com.fs.starfarer.api.campaign.BuffManagerAPI.Buff;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoAPI.CargoItemType;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.HullModFleetEffect;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.impl.campaign.ids.Items;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import data.MyMisc;

/**
 * Hull mod that increases repair rate of a single ship and
 * reduces supply consumption during CR recovery at the cost of metals
 */
public class HE_DedicatedRepairEquipment extends BaseLogisticsHullMod {
   public static final float DAYS_TO_TRIGGER = 0.3F;

   public static final String BONUS_ID = "repair_equipment_bonus";
   public static final float REPAIR_BONUS = 1.5F;
   public static final float SUPPLIES_RECOVERY_BONUS = 0.5F;
   public static final float MIN_CR = 0.1F;

   public static boolean isValidForRepair(FleetMemberAPI repairShip, FleetMemberAPI repairTarget) {
      return repairShip != null && repairTarget != null && repairTarget != repairShip
            && repairShip.getFleetData() != null
            && repairShip.getFleetData().getFleet().getCargo().getCommodityQuantity(
                  Commodities.METALS) > 0
            && repairShip.getRepairTracker().getCR() >= MIN_CR
            && repairShip.getFleetData().getMembersInPriorityOrder().contains(repairTarget)
            && !repairShip.getRepairTracker().isSuspendRepairs()
            && repairTarget.getRepairTracker().getCR() < repairTarget.getRepairTracker().getMaxCR()
            && !repairTarget.getRepairTracker().isSuspendRepairs()
            && !repairTarget.getRepairTracker().isMothballed()
            && !repairTarget.getRepairTracker().isCrashMothballed();
   }

   public static FleetMemberAPI getValidRepairTarget(FleetMemberAPI member) {
      for (FleetMemberAPI it : member.getFleetData().getMembersInPriorityOrder()) {
         if (isValidForRepair(member, it)) {
            return it;
         }
      }
      return null;
   }

   public static class RepairEquipmentBuff implements Buff {
      private static final float BASE_CONVERSION_RATIO = MyMisc.getCommodityConversionRatio(Commodities.SUPPLIES,
            Commodities.METALS);
      public static final float USAGE_TAX = 1.2F;

      public static float getUsedMetalsPerDay(MutableShipStatsAPI stats) {
         return MyMisc.round(MyMisc.getRecoverySuppliesPerDay(stats) * BASE_CONVERSION_RATIO * USAGE_TAX, 2);
      }

      private FleetMemberAPI repairTarget;
      private FleetMemberAPI repairShip;
      private boolean expired = false;
      private float metalsPerDay;

      public RepairEquipmentBuff(FleetMemberAPI repairShip, FleetMemberAPI repairTarget) {
         this.repairTarget = repairTarget;
         this.repairShip = repairShip;
         this.metalsPerDay = getUsedMetalsPerDay(repairTarget.getStats());
      }

      public boolean isExpired() {
         return expired;
      }

      public String getId() {
         return BONUS_ID;
      }

      public void apply(FleetMemberAPI member) {
         member.getStats().getSuppliesToRecover().modifyMult(getId(), SUPPLIES_RECOVERY_BONUS);
         member.getStats().getRepairRatePercentPerDay().modifyMult(getId(),
               REPAIR_BONUS);
      }

      // yes. this uses actual in-game days.
      // no, I dont know why.
      public void advance(float days) {
         if (!isValidForRepair(repairShip, repairTarget)) {
            expired = true;
            return;
         }

         float metalsShouldUse = metalsPerDay * days;
         CargoAPI cargo = repairShip.getFleetData().getFleet().getCargo();
         cargo.removeCommodity(Commodities.METALS, metalsShouldUse);

      }
   };

   class State {
      public float nextTriggerIn;
      public FleetMemberAPI repairTarget;
      public RepairEquipmentBuff buffInstance;
   }

   public boolean isRepairInProgress(Buff buffInstance, FleetMemberAPI repairTarget) {
      return repairTarget != null && buffInstance != null
            && repairTarget.getBuffManager().getBuff(BONUS_ID) == buffInstance
            && !buffInstance.isExpired();
   }

   public Map<FleetMemberAPI, State> state = new WeakHashMap<FleetMemberAPI, State>();

   @Override
   public void advanceInCampaign(FleetMemberAPI member, float amount) {
      State data = state.get(member);

      if (data == null) {
         State s = new State();
         s.nextTriggerIn = DAYS_TO_TRIGGER;
         data = state.put(member, s);
         data = s;
      }

      if (data.nextTriggerIn > 0) {
         data.nextTriggerIn -= Global.getSector().getClock().convertToDays(amount);
         return;
      }

      if (isRepairInProgress(data.buffInstance, data.repairTarget)) {
         data.nextTriggerIn = DAYS_TO_TRIGGER + data.nextTriggerIn;
         return;
      }

      FleetMemberAPI newRepairTarget = getValidRepairTarget(member);
      if (newRepairTarget == null) {
         data.nextTriggerIn = DAYS_TO_TRIGGER + data.nextTriggerIn;
         return;
      }

      data.repairTarget = newRepairTarget;

      data.buffInstance = new RepairEquipmentBuff(member, data.repairTarget);
      data.repairTarget.getBuffManager().addBuffOnlyUpdateStat(data.buffInstance);

      data.nextTriggerIn = DAYS_TO_TRIGGER + data.nextTriggerIn;
   }

   @Override
   public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
      if (index == 0)
         return "" + (int) Math.round((REPAIR_BONUS - 1F) * 100) + "%";
      if (index == 1)
         return "" + (int) Math.round(SUPPLIES_RECOVERY_BONUS * 100) + "%";
      return null;
   }

   @Override
   public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
      return true;
   }

   @Override
   public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width,
         boolean isForModSpec) {
      if (Global.getSettings().getCurrentState() == GameState.TITLE || isForModSpec || ship == null
            || ship.getFleetMember() == null) {
         return;
      }

      float pad = 3f;
      float opad = 10f;
      Color h = Misc.getHighlightColor();
      Color bad = Misc.getNegativeHighlightColor();

      State data = state.get(ship.getFleetMember());

      if (data == null) {
         return;
      }

      if (isRepairInProgress(data.buffInstance, data.repairTarget)) {
         tooltip.addPara("The ship is currently repairing %s. The cost is %s metals per day.", opad, h,
               "" + data.repairTarget.getShipName(),
               "" + (int) Math.round(RepairEquipmentBuff.getUsedMetalsPerDay(data.repairTarget.getStats())));
      } else if (ship.getFleetMember().getFleetData().getFleet().getCargo().getCommodityQuantity(
            Commodities.METALS) <= 0) {
         tooltip.addPara("The ship is lacking metals for repair.", opad, h);
      } else if (ship.getFleetMember().getRepairTracker().getCR() < MIN_CR) {
         // impl/campaign/RepairGantry.java
         LabelAPI label = tooltip.addPara("This ship's combat readiness is below %s " +
               "and the repairs can not be conducted.",
               opad, h,
               "" + (int) Math.round(MIN_CR * 100f) + "%");
         label.setHighlightColors(bad, h);
         label.setHighlight("" + (int) Math.round(MIN_CR * 100f) + "%");
      } else {
         tooltip.addPara("The ship is not repairing anything currently.", opad, h);
      }
   }

   @Override
   public boolean isApplicableToShip(ShipAPI ship) {
      return false;
   }
}
