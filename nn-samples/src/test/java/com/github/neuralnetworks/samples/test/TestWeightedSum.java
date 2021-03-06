package com.github.neuralnetworks.samples.test;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.amd.aparapi.Kernel.EXECUTION_MODE;
import com.github.neuralnetworks.architecture.ConnectionFactory;
import com.github.neuralnetworks.architecture.Connections;
import com.github.neuralnetworks.architecture.FullyConnected;
import com.github.neuralnetworks.architecture.Layer;
import com.github.neuralnetworks.calculation.ConnectionCalculator;
import com.github.neuralnetworks.calculation.operations.OperationsFactory;
import com.github.neuralnetworks.calculation.operations.opencl.OpenCLCore;
import com.github.neuralnetworks.tensor.Tensor;
import com.github.neuralnetworks.tensor.TensorFactory;
import com.github.neuralnetworks.tensor.ValuesProvider;
import com.github.neuralnetworks.tensor.Tensor.TensorIterator;
import com.github.neuralnetworks.training.random.RandomInitializerImpl;
import com.github.neuralnetworks.util.Environment;
import com.github.neuralnetworks.util.RuntimeConfiguration;
import com.github.neuralnetworks.util.RuntimeConfiguration.CalculationProvider;

/**
 * Test class for Conv2DFF operations
 */
@RunWith(Parameterized.class)
@Ignore
@Deprecated // test moved to core project
public class TestWeightedSum
{
	// ////////////////////////////
	// Configuration starts here //
	// ////////////////////////////

	/**
	 * set to > 0 to use as constant seed
	 */
	private static long seed = -1;

	/**
	 * size of the minibatch. Values [1, 256]
	 */
	private int minibatchSize = 128;

	private KernelConfiguration kernelConfiguration;

	/**
	 * This method determines the different parameters of the kernel
	 * Each line of type configurations.add(...) is one configuration
	 * You can comment and uncomment necessary/unnecessary configurations
	 */
	@Parameters
	public static Collection<KernelConfiguration[]> runtimeConfigurations()
	{
		List<KernelConfiguration[]> configurations = new ArrayList<>();

		// configuration 1
//		KernelConfiguration conf1 = new KernelConfiguration();
//		conf1.connection = new ConnectionFactory().conv2d(new Layer(), new Layer(), 3, 3, 2, 2, 2, 1, 1, 1, 0, 0);
//		conf1.kernelRuns = 1000;
//		configurations.add(new KernelConfiguration[] {conf1});;
		
		KernelConfiguration conf2 = new KernelConfiguration();
		conf2.connection = new ConnectionFactory().fullyConnected(new Layer(), new Layer(), 64, 10);
		conf2.kernelRuns = 1;
		conf2.testAparapi = false;
		//conf2.testCpu = false;
		configurations.add(new KernelConfiguration[] {conf2});;

		KernelConfiguration conf3 = new KernelConfiguration();
		conf3.connection = new ConnectionFactory().fullyConnected(new Layer(), new Layer(), 64, 10);
		conf3.kernelRuns = 1;
		conf3.testOpenCL = false;
		conf3.testCpu = false;
		configurations.add(new KernelConfiguration[] {conf3});;

		KernelConfiguration conf4 = new KernelConfiguration();
		conf4.connection = new ConnectionFactory().fullyConnected(new Layer(), new Layer(), 64, 10);
		conf4.kernelRuns = 1000000;
		conf4.testOpenCL = false;
		conf4.testAparapi = false;
		configurations.add(new KernelConfiguration[] {conf4});;
		
		// configuration 3
//		KernelConfiguration conf3 = new KernelConfiguration();
//		conf3.connection = new ConnectionFactory().conv2d(new Layer(), new Layer(), 224, 224, 1, 7, 7, 96, 2, 2, 0, 0);
//		conf3.kernelRuns = 1;
//		configurations.add(new KernelConfiguration[] {conf3});;

		return configurations;
	}

	// //////////////////////////
	// Configuration ends here //
	// //////////////////////////

	private FullyConnected connection; // this is set automatically

	public TestWeightedSum(KernelConfiguration conf)
	{
		this.connection = conf.connection;
		this.kernelConfiguration = conf;
	}

	@Test
	public void test()
	{
		// initialize connection weights and input
		Random r = new Random();
		if (seed > 0)
		{
			r.setSeed(seed);
		}

		new RandomInitializerImpl(r, -1f, 1f).initialize(connection.getWeights());

		ValuesProvider vp = TensorFactory.tensorProvider(connection, minibatchSize, Environment.getInstance().getRuntimeConfiguration().getUseDataSharedMemory());
		Tensor input = vp.get(connection.getInputLayer());
		input.forEach(i -> input.getElements()[i] = r.nextFloat());

		// setup
		List<Connections> connections = new ArrayList<>();
		connections.add(connection);

		System.out.println("START KERNEL CONFIGURATION");

		// OpenCL
		Tensor oclOutput = null;
		if (kernelConfiguration.testOpenCL)
		{
			try
			{
				RuntimeConfiguration oclConf = new RuntimeConfiguration();
				oclConf.setCalculationProvider(CalculationProvider.OPENCL);
				oclConf.setUseDataSharedMemory(false);
				oclConf.setUseWeightsSharedMemory(false);
				oclConf.getOpenCLConfiguration().setAggregateOperations(false);
				oclConf.getOpenCLConfiguration().setSynchronizeAfterOpertation(true);
				oclConf.getAparapiConfiguration().setExecutionMode(EXECUTION_MODE.SEQ);
				Environment.getInstance().setRuntimeConfiguration(oclConf);

				ConnectionCalculator oclWeightedSum = OperationsFactory.weightedSum();
				oclWeightedSum.calculate(connections, vp, connection.getOutputLayer());

				oclOutput = TensorFactory.tensor(vp.get(connection.getOutputLayer()).getDimensions());
				TensorFactory.copy(vp.get(connection.getOutputLayer()), oclOutput);

				oclConf.getOpenCLConfiguration().setSynchronizeAfterOpertation(false);

				// perform "cycles" with the opencl calculator
				long start = System.currentTimeMillis();
				for (int i = 0; i < kernelConfiguration.kernelRuns; i++)
				{
					oclWeightedSum.calculate(connections, vp, connection.getOutputLayer());
				}
				long time = System.currentTimeMillis() - start;

				System.out.println("OpenCL : " + time + " ms (" + (time / 1000) + " s) for " + kernelConfiguration.kernelRuns + " kernel runs, " + ((time * 1000) / kernelConfiguration.kernelRuns)
						+ " micro seconds per kernel run");
			} finally
			{
				OpenCLCore.getInstance().finalizeDeviceAll();
			}
		}

		// CPU
		Tensor cpuOutput = null;
		if (kernelConfiguration.testCpu)
		{
			RuntimeConfiguration cpuConf = new RuntimeConfiguration();
			cpuConf.getAparapiConfiguration().setExecutionMode(EXECUTION_MODE.SEQ);
			cpuConf.setUseDataSharedMemory(false);
			cpuConf.setUseWeightsSharedMemory(false);
			Environment.getInstance().setRuntimeConfiguration(cpuConf);

			ConnectionCalculator cpuWeightedSum = OperationsFactory.weightedSum();

			ValuesProvider cpuVP = TensorFactory.tensorProvider(connection, minibatchSize, Environment.getInstance().getRuntimeConfiguration().getUseDataSharedMemory());
			TensorFactory.copy(input, cpuVP.get(connection.getInputLayer()));

			// prepare the kernel
			cpuWeightedSum.calculate(connections, cpuVP, connection.getOutputLayer());
			cpuOutput = TensorFactory.tensor(cpuVP.get(connection.getOutputLayer()).getDimensions());
			TensorFactory.copy(cpuVP.get(connection.getOutputLayer()), cpuOutput);

			// to file
//			try
//			{
//				Util.toFile("E:\\input.txt", input.getElements());
//				Util.toFile("E:\\weights.txt", connection.getWeights().getElements());
//				Util.toFile("E:\\output.txt", cpuOutput.getElements());
//
//				PrintWriter parameters = new PrintWriter("E:\\parameters.txt");
//				AparapiWeightedSumConnectionCalculator cpucc = (AparapiWeightedSumConnectionCalculator) cpuWeightedSum;
//				parameters.println(OpenCLCore.getKernelOptionsString((Kernel) cpucc.getInputFunctions().get(0)));
//				parameters.close();
//			} catch (FileNotFoundException e)
//			{
//				e.printStackTrace();
//			}

			// measure time
			long start = System.currentTimeMillis();
			for (int i = 0; i < kernelConfiguration.kernelRuns; i++)
			{
				cpuWeightedSum.calculate(connections, cpuVP, connection.getOutputLayer());
			}
			long time = System.currentTimeMillis() - start;

			System.out.println("CPU    : " + time + " ms (" + (time / 1000) + " s) for " + kernelConfiguration.kernelRuns + " kernel runs, " + ((time * 1000) / kernelConfiguration.kernelRuns) + " micro seconds per kernel run");
		}
		
		// Aparapi
		Tensor aparapiOutput = null;
		if (kernelConfiguration.testAparapi)
		{
			RuntimeConfiguration aparapiConf = new RuntimeConfiguration();
			aparapiConf.getAparapiConfiguration().setExecutionMode(EXECUTION_MODE.GPU);
			aparapiConf.setUseDataSharedMemory(false);
			aparapiConf.setUseWeightsSharedMemory(false);
			Environment.getInstance().setRuntimeConfiguration(aparapiConf);
			
			ConnectionCalculator aparapiWeightedSum = OperationsFactory.weightedSum();
			
			ValuesProvider aparapiVP = TensorFactory.tensorProvider(connection, minibatchSize, Environment.getInstance().getRuntimeConfiguration().getUseDataSharedMemory());
			TensorFactory.copy(input, aparapiVP.get(connection.getInputLayer()));
			
			// prepare the kernel
			aparapiWeightedSum.calculate(connections, aparapiVP, connection.getOutputLayer());
			aparapiOutput = TensorFactory.tensor(aparapiVP.get(connection.getOutputLayer()).getDimensions());
			TensorFactory.copy(aparapiVP.get(connection.getOutputLayer()), aparapiOutput);
			
			// measure time
			long start = System.currentTimeMillis();
			for (int i = 0; i < kernelConfiguration.kernelRuns; i++)
			{
				aparapiWeightedSum.calculate(connections, aparapiVP, connection.getOutputLayer());
			}
			long time = System.currentTimeMillis() - start;

			System.out.println("Aparapi: " + time + " ms (" + (time / 1000) + " s) for " + kernelConfiguration.kernelRuns + " kernel runs, " + ((time * 1000) / kernelConfiguration.kernelRuns) + " micro seconds per kernel run");
		}

		if (oclOutput != null && cpuOutput != null)
		{
			TensorIterator oclIt = oclOutput.iterator();
			TensorIterator cpuIt = cpuOutput.iterator();
			while (oclIt.hasNext() && cpuIt.hasNext())
			{
				assertEquals(oclOutput.getElements()[oclIt.next()], cpuOutput.getElements()[cpuIt.next()], 0.0001f);
			}
		}

		System.out.println("END KERNEL CONFIGURATION");
		System.out.println();
	}

	private static class KernelConfiguration
	{
		private FullyConnected connection;

		/**
		 * how many times to execute each opencl kernel. Note that the output array is not erased after each cycle. This means that, while the input is always the same, consecutive executions of the same kernels will
		 * produce different results
		 */
		private int kernelRuns;

		/**
		 * set to true to test using OpenCL
		 */
		private boolean testOpenCL = true;

		/**
		 * set to true to compare the results between OpenCL and Aparapi
		 */
		private boolean testAparapi = true;

		/**
		 * set to true to inlcude CPU testing for performance comparison
		 */
		private boolean testCpu = true;
	}
}
