package gregicadditions.machines.multi.miner;

import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;
import com.google.common.collect.Lists;
import gregicadditions.item.GAMetaBlocks;
import gregtech.api.GTValues;
import gregtech.api.capability.IEnergyContainer;
import gregtech.api.capability.IMultipleTankHandler;
import gregtech.api.capability.impl.EnergyContainerList;
import gregtech.api.capability.impl.FluidTankList;
import gregtech.api.capability.impl.ItemHandlerList;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.api.metatileentity.multiblock.IMultiblockPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.metatileentity.multiblock.MultiblockWithDisplayBase;
import gregtech.api.multiblock.BlockPattern;
import gregtech.api.multiblock.FactoryBlockPattern;
import gregtech.api.multiblock.PatternMatchContext;
import gregtech.api.render.ICubeRenderer;
import gregtech.api.render.Textures;
import gregtech.api.unification.material.Materials;
import gregtech.api.unification.material.type.Material;
import gregtech.api.util.GTUtility;
import gregtech.common.blocks.MetaBlocks;
import gregtech.common.metatileentities.MetaTileEntities;
import gregtech.common.tools.ToolUtility;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public class MetaTileEntityLargeMiner extends MultiblockWithDisplayBase implements Miner {

	private static final MultiblockAbility<?>[] ALLOWED_ABILITIES = {MultiblockAbility.IMPORT_ITEMS, MultiblockAbility.EXPORT_ITEMS, MultiblockAbility.IMPORT_FLUIDS, MultiblockAbility.INPUT_ENERGY};
	public final Miner.Type type;
	private final Material material;
	private AtomicLong x = new AtomicLong(Long.MAX_VALUE), y = new AtomicLong(Long.MAX_VALUE), z = new AtomicLong(Long.MAX_VALUE);
	private AtomicInteger currentChunk = new AtomicInteger(0);
	private IEnergyContainer energyContainer;
	private IMultipleTankHandler importFluidHandler;
	protected IItemHandlerModifiable outputInventory;
	private List<Chunk> chunks = new ArrayList<>();
	private boolean isActive = false;
	private boolean done = false;
	protected boolean wasActiveAndNeedsUpdate;


	public MetaTileEntityLargeMiner(ResourceLocation metaTileEntityId, Miner.Type type, Material material) {
		super(metaTileEntityId);
		this.type = type;
		this.material = material;
		reinitializeStructurePattern();
	}

	@Override
	public void invalidateStructure() {
		super.invalidateStructure();
		resetTileAbilities();
		if (isActive)
			setActive(false);
	}

	@Override
	protected void formStructure(PatternMatchContext context) {
		super.formStructure(context);
		initializeAbilities();
	}

	private void initializeAbilities() {
		this.importFluidHandler = new FluidTankList(true, getAbilities(MultiblockAbility.IMPORT_FLUIDS));
		this.outputInventory = new ItemHandlerList(getAbilities(MultiblockAbility.EXPORT_ITEMS));
		this.energyContainer = new EnergyContainerList(getAbilities(MultiblockAbility.INPUT_ENERGY));
	}

	private void resetTileAbilities() {
		this.importFluidHandler = new FluidTankList(true);
		this.outputInventory = new ItemStackHandler(0);
		this.energyContainer = new EnergyContainerList(Lists.newArrayList());
	}

	public boolean drainEnergy() {
		FluidStack drillingFluid = Materials.DrillingFluid.getFluid(type.drillingFluidConsumePerTick);
		FluidStack canDrain = importFluidHandler.drain(drillingFluid, false);
		if (energyContainer.getEnergyStored() >= energyContainer.getInputVoltage() && canDrain != null && canDrain.amount == type.drillingFluidConsumePerTick) {
			energyContainer.removeEnergy(energyContainer.getInputVoltage());
			importFluidHandler.drain(drillingFluid, true);
			return true;
		}
		return false;
	}

	@Override
	public long getNbBlock() {
		return GTUtility.getTierByVoltage(energyContainer.getInputVoltage()) - GTValues.HV;
	}

	@Override
	protected void updateFormedValid() {
		if (!getWorld().isRemote) {
			if (!done && !drainEnergy()) {
				if (isActive)
					setActive(false);
				return;
			}

			if (!isActive)
				setActive(true);

			WorldServer world = (WorldServer) this.getWorld();
			ChunkPos chunkPos = world.getChunk(getPos()).getPos();
			BlockPos origin = null;
			if (type.chunk / 2.0 > 1.0 && chunks.size() == 0) {
				for (int i = 0; i < Math.floorDiv(type.chunk, 2); i++) {
					BlockPos pos = new BlockPos(chunkPos.getXStart(), getPos().getY(), chunkPos.getZStart());
					origin = pos.north().west();
					chunkPos = world.getChunk(origin).getPos();
				}
				BlockPos tmpX = origin;
				BlockPos tmpZ = origin;
				for (int i = 0; i < type.chunk; i++) {
					for (int j = 0; j < type.chunk; j++) {
						Chunk chunk = world.getChunk(tmpX);
						chunks.add(chunk);
						tmpX = new BlockPos(chunk.getPos().getXEnd(), getPos().getY(), chunk.z).east();
					}
					Chunk chunk = world.getChunk(tmpZ);
					tmpZ = new BlockPos(chunk.x, getPos().getY(), chunk.getPos().getZEnd()).south();
					tmpX = tmpZ;
				}
			}

			if (currentChunk.intValue() == chunks.size()) {
				setActive(false);
				return;
			}

			Chunk chunk = chunks.get(currentChunk.intValue());

			if (x.get() == Long.MAX_VALUE) {
				x.set(chunk.getPos().getXStart());
			}
			if (z.get() == Long.MAX_VALUE) {
				z.set(chunk.getPos().getZStart());
			}
			if (y.get() == Long.MAX_VALUE) {
				y.set(getPos().getY());
			}


			List<BlockPos> blockPos = Miner.getBlockToMinePerChunk(this, x, y, z, chunk.getPos());
			blockPos.forEach(blockPos1 -> {
				NonNullList<ItemStack> itemStacks = NonNullList.create();
				IBlockState blockState = this.getWorld().getBlockState(blockPos1);
				ToolUtility.applyHammerDrops(world.rand, blockState, itemStacks, type.fortune, null);
				if (addItemsToItemHandler(outputInventory, true, itemStacks)) {
					addItemsToItemHandler(outputInventory, false, itemStacks);
					world.destroyBlock(blockPos1, false);
				}
			});

			if (y.get() < 0) {
				currentChunk.incrementAndGet();
				if (currentChunk.get() <= chunks.size()) {
					done = true;
				}else{
					x.set(chunks.get(currentChunk.intValue()).getPos().getXStart());
					z.set(chunks.get(currentChunk.intValue()).getPos().getZStart());
					y.set(getPos().getY());
				}
			}


			if (!getWorld().isRemote && getTimer() % 5 == 0) {
				pushItemsIntoNearbyHandlers(getFrontFacing());
			}
		}

	}

	@Override
	protected BlockPattern createStructurePattern() {
		return material == null || type == null ? null : FactoryBlockPattern.start()
				.aisle("F###F", "F###F", "PPPPP", "#####", "#####", "#####", "#####", "#####", "#####", "#####")
				.aisle("#####", "#####", "PPPPP", "#CEC#", "#####", "#####", "#####", "#####", "#####", "#####")
				.aisle("#####", "#####", "PPPPP", "#CPC#", "#FFF#", "#FFF#", "#FFF#", "##F##", "##F##", "##F##")
				.aisle("#####", "#####", "PPPPP", "#CSC#", "#####", "#####", "#####", "#####", "#####", "#####")
				.aisle("F###F", "F###F", "PPPPP", "#####", "#####", "#####", "#####", "#####", "#####", "#####")
				.where('S', selfPredicate())
				.where('C', statePredicate(getCasingState()).or(abilityPartPredicate(MultiblockAbility.IMPORT_ITEMS, MultiblockAbility.EXPORT_ITEMS, MultiblockAbility.IMPORT_FLUIDS)))
				.where('P', statePredicate(getCasingState()))
				.where('E', tilePredicate((state, tile) -> {
					for (int i = GTValues.EV; i < GTValues.V.length; i++) {
						if (tile.metaTileEntityId.equals(MetaTileEntities.ENERGY_INPUT_HATCH[i].metaTileEntityId))
							return true;
					}
					return false;
				}))
				.where('F', statePredicate(MetaBlocks.FRAMES.get(material).getDefaultState()))
				.where('#', blockWorldState -> true)
				.build();
	}

	@Override
	public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, boolean advanced) {
		tooltip.add(I18n.format("gtadditions.machine.miner.multi.description", type.chunk, type.chunk, type.fortune));
		tooltip.add(I18n.format("gtadditions.machine.miner.fluid_usage", type.drillingFluidConsumePerTick, I18n.format(Materials.DrillingFluid.getFluid(0).getUnlocalizedName())));
	}

	@Override
	protected void addDisplayText(List<ITextComponent> textList) {
		if (this.isStructureFormed()) {
			textList.add(new TextComponentString(String.format("X: %d", x.get())));
			textList.add(new TextComponentString(String.format("Y: %d", y.get())));
			textList.add(new TextComponentString(String.format("Z: %d", z.get())));
			textList.add(new TextComponentString(String.format("chuck: %d", currentChunk.get())));
			textList.add(new TextComponentString(String.format("nb chunk: %d", chunks.size())));
			textList.add(new TextComponentString(String.format("block per tick: %d", getNbBlock())));
		}

		super.addDisplayText(textList);
	}

	public IBlockState getCasingState() {
		return GAMetaBlocks.METAL_CASING.get(material).getDefaultState();
	}

	@Override
	public ICubeRenderer getBaseTexture(IMultiblockPart sourcePart) {
		return GAMetaBlocks.METAL_CASING.get(material);
	}

	@Override
	public MetaTileEntity createMetaTileEntity(MetaTileEntityHolder holder) {
		return new MetaTileEntityLargeMiner(metaTileEntityId, getType(), material);
	}

	@Override
	public Type getType() {
		return type;
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound data) {
		super.writeToNBT(data);
		data.setTag("xPos", new NBTTagLong(x.get()));
		data.setTag("yPos", new NBTTagLong(y.get()));
		data.setTag("zPos", new NBTTagLong(z.get()));
		data.setTag("chunk", new NBTTagInt(currentChunk.get()));
		data.setTag("done", new NBTTagInt(done ? 1 : 0));
		return data;
	}

	@Override
	public void readFromNBT(NBTTagCompound data) {
		super.readFromNBT(data);
		x.set(data.getLong("xPos"));
		y.set(data.getLong("yPos"));
		z.set(data.getLong("zPos"));
		currentChunk.set(data.getInteger("chunk"));
		done = data.getInteger("done") != 0;
	}

	public Material getMaterial() {
		return material;
	}

	@Override
	public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
		super.renderMetaTileEntity(renderState, translation, pipeline);
		Textures.MULTIBLOCK_WORKABLE_OVERLAY.render(renderState, translation, pipeline, getFrontFacing(), isActive);
	}

	protected void setActive(boolean active) {
		this.isActive = active;
		markDirty();
		if (!getWorld().isRemote) {
			writeCustomData(1, buf -> buf.writeBoolean(active));
		}
	}

	@Override
	public void receiveCustomData(int dataId, PacketBuffer buf) {
		super.receiveCustomData(dataId, buf);
		if (dataId == 1) {
			this.isActive = buf.readBoolean();
			getHolder().scheduleChunkForRenderUpdate();
		}
	}

}